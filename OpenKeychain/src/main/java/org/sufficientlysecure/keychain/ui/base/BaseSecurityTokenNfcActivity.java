/*
 * Copyright (C) 2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2015 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 * Copyright (C) 2013-2014 Signe Rüsch
 * Copyright (C) 2013-2014 Philipp Jakubeit
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui.base;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.interfaces.RSAPrivateCrtKey;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;

import nordpol.Apdu;
import nordpol.android.TagDispatcher;
import nordpol.android.AndroidCard;
import nordpol.android.OnDiscoveredTagListener;
import nordpol.IsoCard;

import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.CachedPublicKeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.service.PassphraseCacheService.KeyNotFoundException;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.ui.CreateKeyActivity;
import org.sufficientlysecure.keychain.ui.PassphraseDialogActivity;
import org.sufficientlysecure.keychain.ui.ViewKeyActivity;
import org.sufficientlysecure.keychain.ui.dialog.FidesmoInstallDialog;
import org.sufficientlysecure.keychain.ui.dialog.FidesmoPgpInstallDialog;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.util.Notify.Style;
import org.sufficientlysecure.keychain.util.Iso7816TLV;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Passphrase;

public abstract class BaseSecurityTokenNfcActivity extends BaseActivity implements OnDiscoveredTagListener {
    public static final int REQUEST_CODE_PIN = 1;

    public static final String EXTRA_TAG_HANDLING_ENABLED = "tag_handling_enabled";

    // Fidesmo constants
    private static final String FIDESMO_APPS_AID_PREFIX = "A000000617";
    private static final String FIDESMO_APP_PACKAGE = "com.fidesmo.sec.android";

    protected Passphrase mPin;
    protected Passphrase mAdminPin;
    protected boolean mPw1ValidForMultipleSignatures;
    protected boolean mPw1ValidatedForSignature;
    protected boolean mPw1ValidatedForDecrypt; // Mode 82 does other things; consider renaming?
    protected boolean mPw3Validated;
    protected TagDispatcher mTagDispatcher;
    private IsoCard mIsoCard;
    private boolean mTagHandlingEnabled;

    private static final int TIMEOUT = 100000;

    private byte[] mNfcFingerprints;
    private String mNfcUserId;
    private byte[] mNfcAid;

    /**
     * Override to change UI before NFC handling (UI thread)
     */
    protected void onNfcPreExecute() {
    }

    /**
     * Override to implement NFC operations (background thread)
     */
    protected void doNfcInBackground() throws IOException {
        mNfcFingerprints = nfcGetFingerprints();
        mNfcUserId = nfcGetUserId();
        mNfcAid = nfcGetAid();
    }

    /**
     * Override to handle result of NFC operations (UI thread)
     */
    protected void onNfcPostExecute() {

        final long subKeyId = KeyFormattingUtils.getKeyIdFromFingerprint(mNfcFingerprints);

        try {
            CachedPublicKeyRing ring = new ProviderHelper(this).getCachedPublicKeyRing(
                    KeyRings.buildUnifiedKeyRingsFindBySubkeyUri(subKeyId));
            long masterKeyId = ring.getMasterKeyId();

            Intent intent = new Intent(this, ViewKeyActivity.class);
            intent.setData(KeyRings.buildGenericKeyRingUri(masterKeyId));
            intent.putExtra(ViewKeyActivity.EXTRA_SECURITY_TOKEN_AID, mNfcAid);
            intent.putExtra(ViewKeyActivity.EXTRA_SECURITY_TOKEN_USER_ID, mNfcUserId);
            intent.putExtra(ViewKeyActivity.EXTRA_SECURITY_TOKEN_FINGERPRINTS, mNfcFingerprints);
            startActivity(intent);
        } catch (PgpKeyNotFoundException e) {
            Intent intent = new Intent(this, CreateKeyActivity.class);
            intent.putExtra(CreateKeyActivity.EXTRA_NFC_AID, mNfcAid);
            intent.putExtra(CreateKeyActivity.EXTRA_NFC_USER_ID, mNfcUserId);
            intent.putExtra(CreateKeyActivity.EXTRA_NFC_FINGERPRINTS, mNfcFingerprints);
            startActivity(intent);
        }
    }

    /**
     * Override to use something different than Notify (UI thread)
     */
    protected void onNfcError(String error) {
        Notify.create(this, error, Style.WARN).show();
    }

    /**
     * Override to do something when PIN is wrong, e.g., clear passphrases (UI thread)
     */
    protected void onNfcPinError(String error) {
        onNfcError(error);
    }

    public void tagDiscovered(final Tag tag) {
        // Actual NFC operations are executed in doInBackground to not block the UI thread
        if(!mTagHandlingEnabled)
            return;
        new AsyncTask<Void, Void, IOException>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                onNfcPreExecute();
            }

            @Override
            protected IOException doInBackground(Void... params) {
                try {
                    handleTagDiscovered(tag);
                } catch (IOException e) {
                    return e;
                }

                return null;
            }

            @Override
            protected void onPostExecute(IOException exception) {
                super.onPostExecute(exception);

                if (exception != null) {
                    handleNfcError(exception);
                    return;
                }

                onNfcPostExecute();
            }
        }.execute();
    }

    protected void pauseTagHandling() {
        mTagHandlingEnabled = false;
    }

    protected void resumeTagHandling() {
        mTagHandlingEnabled = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTagDispatcher = TagDispatcher.get(this, this, false, false, true, false);

        // Check whether we're recreating a previously destroyed instance
        if (savedInstanceState != null) {
            // Restore value of members from saved state
            mTagHandlingEnabled = savedInstanceState.getBoolean(EXTRA_TAG_HANDLING_ENABLED);
        } else {
            mTagHandlingEnabled = true;
        }

        Intent intent = getIntent();
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            throw new AssertionError("should not happen: NfcOperationActivity.onCreate is called instead of onNewIntent!");
        }

    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(EXTRA_TAG_HANDLING_ENABLED, mTagHandlingEnabled);
    }

    /**
     * This activity is started as a singleTop activity.
     * All new NFC Intents which are delivered to this activity are handled here
     */
    @Override
    public void onNewIntent(final Intent intent) {
        mTagDispatcher.interceptIntent(intent);
    }

    private void handleNfcError(IOException e) {

        if (e instanceof TagLostException) {
            onNfcError(getString(R.string.security_token_error_tag_lost));
            return;
        }

        if (e instanceof IsoDepNotSupportedException) {
            onNfcError(getString(R.string.security_token_error_iso_dep_not_supported));
            return;
        }

        short status;
        if (e instanceof CardException) {
            status = ((CardException) e).getResponseCode();
        } else {
            status = -1;
        }

        // Wrong PIN, a status of 63CX indicates X attempts remaining.
        if ((status & (short) 0xFFF0) == 0x63C0) {
            int tries = status & 0x000F;
            // hook to do something different when PIN is wrong
            onNfcPinError(getResources().getQuantityString(R.plurals.security_token_error_pin, tries, tries));
            return;
        }

        // Otherwise, all status codes are fixed values.
        switch (status) {
            // These errors should not occur in everyday use; if they are returned, it means we
            // made a mistake sending data to the token, or the token is misbehaving.
            case 0x6A80: {
                onNfcError(getString(R.string.security_token_error_bad_data));
                break;
            }
            case 0x6883: {
                onNfcError(getString(R.string.security_token_error_chaining_error));
                break;
            }
            case 0x6B00: {
                onNfcError(getString(R.string.security_token_error_header, "P1/P2"));
                break;
            }
            case 0x6D00: {
                onNfcError(getString(R.string.security_token_error_header, "INS"));
                break;
            }
            case 0x6E00: {
                onNfcError(getString(R.string.security_token_error_header, "CLA"));
                break;
            }
            // These error conditions are more likely to be experienced by an end user.
            case 0x6285: {
                onNfcError(getString(R.string.security_token_error_terminated));
                break;
            }
            case 0x6700: {
                onNfcPinError(getString(R.string.security_token_error_wrong_length));
                break;
            }
            case 0x6982: {
                onNfcError(getString(R.string.security_token_error_security_not_satisfied));
                break;
            }
            case 0x6983: {
                onNfcError(getString(R.string.security_token_error_authentication_blocked));
                break;
            }
            case 0x6985: {
                onNfcError(getString(R.string.security_token_error_conditions_not_satisfied));
                break;
            }
            // 6A88 is "Not Found" in the spec, but Yubikey also returns 6A83 for this in some cases.
            case 0x6A88:
            case 0x6A83: {
                onNfcError(getString(R.string.security_token_error_data_not_found));
                break;
            }
            // 6F00 is a JavaCard proprietary status code, SW_UNKNOWN, and usually represents an
            // unhandled exception on the security token.
            case 0x6F00: {
                onNfcError(getString(R.string.security_token_error_unknown));
                break;
            }
            // 6A82 app not installed on security token!
            case 0x6A82: {
                if (isFidesmoDevice()) {
                    // Check if the Fidesmo app is installed
                    if (isAndroidAppInstalled(FIDESMO_APP_PACKAGE)) {
                        promptFidesmoPgpInstall();
                    } else {
                        promptFidesmoAppInstall();
                    }
                } else { // Other (possibly) compatible hardware
                    onNfcError(getString(R.string.security_token_error_pgp_app_not_installed));
                }
                break;
            }
            default: {
                onNfcError(getString(R.string.security_token_error, e.getMessage()));
                break;
            }
        }

    }

    /**
     * Called when the system is about to start resuming a previous activity,
     * disables NFC Foreground Dispatch
     */
    public void onPause() {
        super.onPause();
        Log.d(Constants.TAG, "BaseNfcActivity.onPause");

        mTagDispatcher.disableExclusiveNfc();
    }

    /**
     * Called when the activity will start interacting with the user,
     * enables NFC Foreground Dispatch
     */
    public void onResume() {
        super.onResume();
        Log.d(Constants.TAG, "BaseNfcActivity.onResume");
        mTagDispatcher.enableExclusiveNfc();
    }

    protected void obtainSecurityTokenPin(RequiredInputParcel requiredInput) {

        try {
            Passphrase passphrase = PassphraseCacheService.getCachedPassphrase(this,
                    requiredInput.getMasterKeyId(), requiredInput.getSubKeyId());
            if (passphrase != null) {
                mPin = passphrase;
                return;
            }

            Intent intent = new Intent(this, PassphraseDialogActivity.class);
            intent.putExtra(PassphraseDialogActivity.EXTRA_REQUIRED_INPUT,
                    RequiredInputParcel.createRequiredPassphrase(requiredInput));
            startActivityForResult(intent, REQUEST_CODE_PIN);
        } catch (KeyNotFoundException e) {
            throw new AssertionError(
                    "tried to find passphrase for non-existing key. this is a programming error!");
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_PIN: {
                if (resultCode != Activity.RESULT_OK) {
                    setResult(resultCode);
                    finish();
                    return;
                }
                CryptoInputParcel input = data.getParcelableExtra(PassphraseDialogActivity.RESULT_CRYPTO_INPUT);
                mPin = input.getPassphrase();
                break;
            }
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** Handle NFC communication and return a result.
     *
     * This method is called by onNewIntent above upon discovery of an NFC tag.
     * It handles initialization and login to the application, subsequently
     * calls either nfcCalculateSignature() or nfcDecryptSessionKey(), then
     * finishes the activity with an appropriate result.
     *
     * On general communication, see also
     * http://www.cardwerk.com/smartcards/smartcard_standard_ISO7816-4_annex-a.aspx
     *
     * References to pages are generally related to the OpenPGP Application
     * on ISO SmartCard Systems specification.
     *
     */
    protected void handleTagDiscovered(Tag tag) throws IOException {

        // Connect to the detected tag, setting a couple of settings
        mIsoCard = AndroidCard.get(tag);
        if (mIsoCard == null) {
            throw new IsoDepNotSupportedException("Tag does not support ISO-DEP (ISO 14443-4)");
        }
        mIsoCard.setTimeout(TIMEOUT); // timeout is set to 100 seconds to avoid cancellation during calculation
        mIsoCard.connect();

        // SW1/2 0x9000 is the generic "ok" response, which we expect most of the time.
        // See specification, page 51
        String accepted = "9000";

        // Command APDU (page 51) for SELECT FILE command (page 29)
        String opening =
                "00" // CLA
                        + "A4" // INS
                        + "04" // P1
                        + "00" // P2
                        + "06" // Lc (number of bytes)
                        + "D27600012401" // Data (6 bytes)
                        + "00"; // Le
        String response = nfcCommunicate(opening);  // activate connection
        if ( ! response.endsWith(accepted) ) {
            throw new CardException("Initialization failed!", parseCardStatus(response));
        }

        byte[] pwStatusBytes = nfcGetPwStatusBytes();
        mPw1ValidForMultipleSignatures = (pwStatusBytes[0] == 1);
        mPw1ValidatedForSignature = false;
        mPw1ValidatedForDecrypt = false;
        mPw3Validated = false;

        doNfcInBackground();

    }

    public boolean isNfcConnected() {
        return mIsoCard.isConnected();
    }

    /** Return the key id from application specific data stored on tag, or null
     * if it doesn't exist.
     *
     * @param idx Index of the key to return the fingerprint from.
     * @return The long key id of the requested key, or null if not found.
     */
    public Long nfcGetKeyId(int idx) throws IOException {
        byte[] fp = nfcGetMasterKeyFingerprint(idx);
        if (fp == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(fp);
        // skip first 12 bytes of the fingerprint
        buf.position(12);
        // the last eight bytes are the key id (big endian, which is default order in ByteBuffer)
        return buf.getLong();
    }

    /** Return fingerprints of all keys from application specific data stored
     * on tag, or null if data not available.
     *
     * @return The fingerprints of all subkeys in a contiguous byte array.
     */
    public byte[] nfcGetFingerprints() throws IOException {
        String data = "00CA006E00";
        byte[] buf = mIsoCard.transceive(Hex.decode(data));

        Iso7816TLV tlv = Iso7816TLV.readSingle(buf, true);
        Log.d(Constants.TAG, "nfcGetFingerprints() Iso7816TLV tlv data:\n" + tlv.prettyPrint());

        Iso7816TLV fptlv = Iso7816TLV.findRecursive(tlv, 0xc5);
        if (fptlv == null) {
            return null;
        }

        return fptlv.mV;
    }

    /** Return the PW Status Bytes from the token. This is a simple DO; no TLV decoding needed.
     *
     * @return Seven bytes in fixed format, plus 0x9000 status word at the end.
     */
    public byte[] nfcGetPwStatusBytes() throws IOException {
        String data = "00CA00C400";
        return mIsoCard.transceive(Hex.decode(data));
    }

    /** Return the fingerprint from application specific data stored on tag, or
     * null if it doesn't exist.
     *
     * @param idx Index of the key to return the fingerprint from.
     * @return The fingerprint of the requested key, or null if not found.
     */
    public byte[] nfcGetMasterKeyFingerprint(int idx) throws IOException {
        byte[] data = nfcGetFingerprints();
        if (data == null) {
            return null;
        }

        // return the master key fingerprint
        ByteBuffer fpbuf = ByteBuffer.wrap(data);
        byte[] fp = new byte[20];
        fpbuf.position(idx * 20);
        fpbuf.get(fp, 0, 20);

        return fp;
    }

    public byte[] nfcGetAid() throws IOException {
        String info = "00CA004F00";
        return mIsoCard.transceive(Hex.decode(info));
    }

    public String nfcGetUserId() throws IOException {
        String info = "00CA006500";
        return nfcGetHolderName(nfcCommunicate(info));
    }

    /**
     * Calls to calculate the signature and returns the MPI value
     *
     * @param hash the hash for signing
     * @return a big integer representing the MPI for the given hash
     */
    public byte[] nfcCalculateSignature(byte[] hash, int hashAlgo) throws IOException {
        if (!mPw1ValidatedForSignature) {
            nfcVerifyPIN(0x81); // (Verify PW1 with mode 81 for signing)
        }

        // dsi, including Lc
        String dsi;

        Log.i(Constants.TAG, "Hash: " + hashAlgo);
        switch (hashAlgo) {
            case HashAlgorithmTags.SHA1:
                if (hash.length != 20) {
                    throw new IOException("Bad hash length (" + hash.length + ", expected 10!");
                }
                dsi = "23" // Lc
                        + "3021" // Tag/Length of Sequence, the 0x21 includes all following 33 bytes
                        + "3009" // Tag/Length of Sequence, the 0x09 are the following header bytes
                        + "0605" + "2B0E03021A" // OID of SHA1
                        + "0500" // TLV coding of ZERO
                        + "0414" + getHex(hash); // 0x14 are 20 hash bytes
                break;
            case HashAlgorithmTags.RIPEMD160:
                if (hash.length != 20) {
                    throw new IOException("Bad hash length (" + hash.length + ", expected 20!");
                }
                dsi = "233021300906052B2403020105000414" + getHex(hash);
                break;
            case HashAlgorithmTags.SHA224:
                if (hash.length != 28) {
                    throw new IOException("Bad hash length (" + hash.length + ", expected 28!");
                }
                dsi = "2F302D300D06096086480165030402040500041C" + getHex(hash);
                break;
            case HashAlgorithmTags.SHA256:
                if (hash.length != 32) {
                    throw new IOException("Bad hash length (" + hash.length + ", expected 32!");
                }
                dsi = "333031300D060960864801650304020105000420" + getHex(hash);
                break;
            case HashAlgorithmTags.SHA384:
                if (hash.length != 48) {
                    throw new IOException("Bad hash length (" + hash.length + ", expected 48!");
                }
                dsi = "433041300D060960864801650304020205000430" + getHex(hash);
                break;
            case HashAlgorithmTags.SHA512:
                if (hash.length != 64) {
                    throw new IOException("Bad hash length (" + hash.length + ", expected 64!");
                }
                dsi = "533051300D060960864801650304020305000440" + getHex(hash);
                break;
            default:
                throw new IOException("Not supported hash algo!");
        }

        // Command APDU for PERFORM SECURITY OPERATION: COMPUTE DIGITAL SIGNATURE (page 37)
        String apdu  =
                "002A9E9A" // CLA, INS, P1, P2
                        + dsi // digital signature input
                        + "00"; // Le

        String response = nfcCommunicate(apdu);

        // split up response into signature and status
        String status = response.substring(response.length()-4);
        String signature = response.substring(0, response.length() - 4);

        // while we are getting 0x61 status codes, retrieve more data
        while (status.substring(0, 2).equals("61")) {
            Log.d(Constants.TAG, "requesting more data, status " + status);
            // Send GET RESPONSE command
            response = nfcCommunicate("00C00000" + status.substring(2));
            status = response.substring(response.length()-4);
            signature += response.substring(0, response.length()-4);
        }

        Log.d(Constants.TAG, "final response:" + status);

        if (!mPw1ValidForMultipleSignatures) {
            mPw1ValidatedForSignature = false;
        }

        if ( ! "9000".equals(status)) {
            throw new CardException("Bad NFC response code: " + status, parseCardStatus(response));
        }

        // Make sure the signature we received is actually the expected number of bytes long!
        if (signature.length() != 256 && signature.length() != 512) {
            throw new IOException("Bad signature length! Expected 128 or 256 bytes, got " + signature.length() / 2);
        }

        return Hex.decode(signature);
    }

    /**
     * Calls to calculate the signature and returns the MPI value
     *
     * @param encryptedSessionKey the encoded session key
     * @return the decoded session key
     */
    public byte[] nfcDecryptSessionKey(byte[] encryptedSessionKey) throws IOException {
        if (!mPw1ValidatedForDecrypt) {
            nfcVerifyPIN(0x82); // (Verify PW1 with mode 82 for decryption)
        }

        String firstApdu = "102a8086fe";
        String secondApdu = "002a808603";
        String le = "00";

        byte[] one = new byte[254];
        // leave out first byte:
        System.arraycopy(encryptedSessionKey, 1, one, 0, one.length);

        byte[] two = new byte[encryptedSessionKey.length - 1 - one.length];
        for (int i = 0; i < two.length; i++) {
            two[i] = encryptedSessionKey[i + one.length + 1];
        }

        String first = nfcCommunicate(firstApdu + getHex(one));
        String second = nfcCommunicate(secondApdu + getHex(two) + le);

        String decryptedSessionKey = nfcGetDataField(second);

        return Hex.decode(decryptedSessionKey);
    }

    /** Verifies the user's PW1 or PW3 with the appropriate mode.
     *
     * @param mode For PW1, this is 0x81 for signing, 0x82 for everything else.
     *             For PW3 (Admin PIN), mode is 0x83.
     */
    public void nfcVerifyPIN(int mode) throws IOException {
        if (mPin != null || mode == 0x83) {

            byte[] pin;
            if (mode == 0x83) {
                pin = mAdminPin.toStringUnsafe().getBytes();
            } else {
                pin = mPin.toStringUnsafe().getBytes();
            }

            // SW1/2 0x9000 is the generic "ok" response, which we expect most of the time.
            // See specification, page 51
            String accepted = "9000";
            String response = tryPin(mode, pin); // login
            if (!response.equals(accepted)) {
                throw new CardException("Bad PIN!", parseCardStatus(response));
            }

            if (mode == 0x81) {
                mPw1ValidatedForSignature = true;
            } else if (mode == 0x82) {
                mPw1ValidatedForDecrypt = true;
            } else if (mode == 0x83) {
                mPw3Validated = true;
            }
        }
    }

    public void nfcResetCard() throws IOException {
        String accepted = "9000";

        // try wrong PIN 4 times until counter goes to C0
        byte[] pin = "XXXXXX".getBytes();
        for (int i = 0; i <= 4; i++) {
            String response = tryPin(0x81, pin);
            if (response.equals(accepted)) { // Should NOT accept!
                throw new CardException("Should never happen, XXXXXX has been accepted!", parseCardStatus(response));
            }
        }

        // try wrong Admin PIN 4 times until counter goes to C0
        byte[] adminPin = "XXXXXXXX".getBytes();
        for (int i = 0; i <= 4; i++) {
            String response = tryPin(0x83, adminPin);
            if (response.equals(accepted)) { // Should NOT accept!
                throw new CardException("Should never happen, XXXXXXXX has been accepted", parseCardStatus(response));
            }
        }

        // reactivate token!
        String reactivate1 = "00" + "e6" + "00" + "00";
        String reactivate2 = "00" + "44" + "00" + "00";
        String response1 = nfcCommunicate(reactivate1);
        String response2 = nfcCommunicate(reactivate2);
        if (!response1.equals(accepted) || !response2.equals(accepted)) {
            throw new CardException("Reactivating failed!", parseCardStatus(response1));
        }

    }

    private String tryPin(int mode, byte[] pin) throws IOException {
        // Command APDU for VERIFY command (page 32)
        String login =
                "00" // CLA
                        + "20" // INS
                        + "00" // P1
                        + String.format("%02x", mode) // P2
                        + String.format("%02x", pin.length) // Lc
                        + Hex.toHexString(pin);

        return nfcCommunicate(login);
    }

    /** Modifies the user's PW1 or PW3. Before sending, the new PIN will be validated for
     *  conformance to the token's requirements for key length.
     *
     * @param pw For PW1, this is 0x81. For PW3 (Admin PIN), mode is 0x83.
     * @param newPin The new PW1 or PW3.
     */
    public void nfcModifyPIN(int pw, byte[] newPin) throws IOException {
        final int MAX_PW1_LENGTH_INDEX = 1;
        final int MAX_PW3_LENGTH_INDEX = 3;

        byte[] pwStatusBytes = nfcGetPwStatusBytes();

        if (pw == 0x81) {
            if (newPin.length < 6 || newPin.length > pwStatusBytes[MAX_PW1_LENGTH_INDEX]) {
                throw new IOException("Invalid PIN length");
            }
        } else if (pw == 0x83) {
            if (newPin.length < 8 || newPin.length > pwStatusBytes[MAX_PW3_LENGTH_INDEX]) {
                throw new IOException("Invalid PIN length");
            }
        } else {
            throw new IOException("Invalid PW index for modify PIN operation");
        }

        byte[] pin;
        if (pw == 0x83) {
            pin = mAdminPin.toStringUnsafe().getBytes();
        } else {
            pin = mPin.toStringUnsafe().getBytes();
        }

        // Command APDU for CHANGE REFERENCE DATA command (page 32)
        String changeReferenceDataApdu = "00" // CLA
                + "24" // INS
                + "00" // P1
                + String.format("%02x", pw) // P2
                + String.format("%02x", pin.length + newPin.length) // Lc
                + getHex(pin)
                + getHex(newPin);
        String response = nfcCommunicate(changeReferenceDataApdu); // change PIN
        if (!response.equals("9000")) {
            throw new CardException("Failed to change PIN", parseCardStatus(response));
        }
    }

    /**
     * Stores a data object on the token. Automatically validates the proper PIN for the operation.
     * Supported for all data objects < 255 bytes in length. Only the cardholder certificate
     * (0x7F21) can exceed this length.
     *
     * @param dataObject The data object to be stored.
     * @param data The data to store in the object
     */
    public void nfcPutData(int dataObject, byte[] data) throws IOException {
        if (data.length > 254) {
            throw new IOException("Cannot PUT DATA with length > 254");
        }
        if (dataObject == 0x0101 || dataObject == 0x0103) {
            if (!mPw1ValidatedForDecrypt) {
                nfcVerifyPIN(0x82); // (Verify PW1 for non-signing operations)
            }
        } else if (!mPw3Validated) {
            nfcVerifyPIN(0x83); // (Verify PW3)
        }

        String putDataApdu = "00" // CLA
                + "DA" // INS
                + String.format("%02x", (dataObject & 0xFF00) >> 8) // P1
                + String.format("%02x", dataObject & 0xFF) // P2
                + String.format("%02x", data.length) // Lc
                + getHex(data);

        String response = nfcCommunicate(putDataApdu); // put data
        if (!response.equals("9000")) {
            throw new CardException("Failed to put data.", parseCardStatus(response));
        }
    }

    /**
     * Puts a key on the token in the given slot.
     *
     * @param slot The slot on the token where the key should be stored:
     *             0xB6: Signature Key
     *             0xB8: Decipherment Key
     *             0xA4: Authentication Key
     */
    public void nfcPutKey(int slot, CanonicalizedSecretKey secretKey, Passphrase passphrase)
            throws IOException {
        if (slot != 0xB6 && slot != 0xB8 && slot != 0xA4) {
            throw new IOException("Invalid key slot");
        }

        RSAPrivateCrtKey crtSecretKey;
        try {
            secretKey.unlock(passphrase);
            crtSecretKey = secretKey.getCrtSecretKey();
        } catch (PgpGeneralException e) {
            throw new IOException(e.getMessage());
        }

        // Shouldn't happen; the UI should block the user from getting an incompatible key this far.
        if (crtSecretKey.getModulus().bitLength() > 2048) {
            throw new IOException("Key too large to export to Security Token.");
        }

        // Should happen only rarely; all GnuPG keys since 2006 use public exponent 65537.
        if (!crtSecretKey.getPublicExponent().equals(new BigInteger("65537"))) {
            throw new IOException("Invalid public exponent for smart Security Token.");
        }

        if (!mPw3Validated) {
            nfcVerifyPIN(0x83); // (Verify PW3 with mode 83)
        }

        byte[] header= Hex.decode(
                "4D82" + "03A2"      // Extended header list 4D82, length of 930 bytes. (page 23)
                + String.format("%02x", slot) + "00" // CRT to indicate targeted key, no length
                + "7F48" + "15"      // Private key template 0x7F48, length 21 (decimal, 0x15 hex)
                + "9103"             // Public modulus, length 3
                + "928180"           // Prime P, length 128
                + "938180"           // Prime Q, length 128
                + "948180"           // Coefficient (1/q mod p), length 128
                + "958180"           // Prime exponent P (d mod (p - 1)), length 128
                + "968180"           // Prime exponent Q (d mod (1 - 1)), length 128
                + "97820100"         // Modulus, length 256, last item in private key template
                + "5F48" + "820383");// DO 5F48; 899 bytes of concatenated key data will follow
        byte[] dataToSend = new byte[934];
        byte[] currentKeyObject;
        int offset = 0;

        System.arraycopy(header, 0, dataToSend, offset, header.length);
        offset += header.length;
        currentKeyObject = crtSecretKey.getPublicExponent().toByteArray();
        System.arraycopy(currentKeyObject, 0, dataToSend, offset, 3);
        offset += 3;
        // NOTE: For a 2048-bit key, these lengths are fixed. However, bigint includes a leading 0
        // in the array to represent sign, so we take care to set the offset to 1 if necessary.
        currentKeyObject = crtSecretKey.getPrimeP().toByteArray();
        System.arraycopy(currentKeyObject, currentKeyObject.length - 128, dataToSend, offset, 128);
        Arrays.fill(currentKeyObject, (byte)0);
        offset += 128;
        currentKeyObject = crtSecretKey.getPrimeQ().toByteArray();
        System.arraycopy(currentKeyObject, currentKeyObject.length - 128, dataToSend, offset, 128);
        Arrays.fill(currentKeyObject, (byte)0);
        offset += 128;
        currentKeyObject = crtSecretKey.getCrtCoefficient().toByteArray();
        System.arraycopy(currentKeyObject, currentKeyObject.length - 128, dataToSend, offset, 128);
        Arrays.fill(currentKeyObject, (byte)0);
        offset += 128;
        currentKeyObject = crtSecretKey.getPrimeExponentP().toByteArray();
        System.arraycopy(currentKeyObject, currentKeyObject.length - 128, dataToSend, offset, 128);
        Arrays.fill(currentKeyObject, (byte)0);
        offset += 128;
        currentKeyObject = crtSecretKey.getPrimeExponentQ().toByteArray();
        System.arraycopy(currentKeyObject, currentKeyObject.length - 128, dataToSend, offset, 128);
        Arrays.fill(currentKeyObject, (byte)0);
        offset += 128;
        currentKeyObject = crtSecretKey.getModulus().toByteArray();
        System.arraycopy(currentKeyObject, currentKeyObject.length - 256, dataToSend, offset, 256);

        String putKeyCommand = "10DB3FFF";
        String lastPutKeyCommand = "00DB3FFF";

        // Now we're ready to communicate with the token.
        offset = 0;
        String response;
        while(offset < dataToSend.length) {
            int dataRemaining = dataToSend.length - offset;
            if (dataRemaining > 254) {
                response = nfcCommunicate(
                        putKeyCommand + "FE" + Hex.toHexString(dataToSend, offset, 254)
                );
                offset += 254;
            } else {
                int length = dataToSend.length - offset;
                response = nfcCommunicate(
                        lastPutKeyCommand + String.format("%02x", length)
                        + Hex.toHexString(dataToSend, offset, length));
                offset += length;
            }

            if (!response.endsWith("9000")) {
                throw new CardException("Key export to Security Token failed", parseCardStatus(response));
            }
        }

        // Clear array with secret data before we return.
        Arrays.fill(dataToSend, (byte) 0);
    }

    /**
     * Parses out the status word from a JavaCard response string.
     *
     * @param response A hex string with the response from the token
     * @return A short indicating the SW1/SW2, or 0 if a status could not be determined.
     */
    short parseCardStatus(String response) {
        if (response.length() < 4) {
            return 0; // invalid input
        }

        try {
            return Short.parseShort(response.substring(response.length() - 4), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String nfcGetHolderName(String name) {
        try {
            String slength;
            int ilength;
            name = name.substring(6);
            slength = name.substring(0, 2);
            ilength = Integer.parseInt(slength, 16) * 2;
            name = name.substring(2, ilength + 2);
            name = (new String(Hex.decode(name))).replace('<', ' ');
            return name;
        } catch (IndexOutOfBoundsException e) {
            // try-catch for https://github.com/FluffyKaon/OpenPGP-Card
            // Note: This should not happen, but happens with
            // https://github.com/FluffyKaon/OpenPGP-Card, thus return an empty string for now!

            Log.e(Constants.TAG, "Couldn't get holder name, returning empty string!", e);
            return "";
        }
    }

    private String nfcGetDataField(String output) {
        return output.substring(0, output.length() - 4);
    }

    public String nfcCommunicate(String apdu) throws IOException {
        return getHex(mIsoCard.transceive(Hex.decode(apdu)));
    }

    public static String getHex(byte[] raw) {
        return new String(Hex.encode(raw));
    }

    public class IsoDepNotSupportedException extends IOException {

        public IsoDepNotSupportedException(String detailMessage) {
            super(detailMessage);
        }

    }

    public class CardException extends IOException {
        private short mResponseCode;

        public CardException(String detailMessage, short responseCode) {
            super(detailMessage);
            mResponseCode = responseCode;
        }

        public short getResponseCode() {
            return mResponseCode;
        }

    }

    private boolean isFidesmoDevice() {
        if (isNfcConnected()) { // Check if we can still talk to the card
            try {
                // By trying to select any apps that have the Fidesmo AID prefix we can
                // see if it is a Fidesmo device or not
                byte[] mSelectResponse = mIsoCard.transceive(Apdu.select(FIDESMO_APPS_AID_PREFIX));
                // Compare the status returned by our select with the OK status code
                return Apdu.hasStatus(mSelectResponse, Apdu.OK_APDU);
            } catch (IOException e) {
                Log.e(Constants.TAG, "Card communication failed!", e);
            }
        }
        return false;
    }

    /**
     * Ask user if she wants to install PGP onto her Fidesmo device
      */
    private void promptFidesmoPgpInstall() {
        FidesmoPgpInstallDialog mFidesmoPgpInstallDialog = new FidesmoPgpInstallDialog();
        mFidesmoPgpInstallDialog.show(getSupportFragmentManager(), "mFidesmoPgpInstallDialog");
    }

    /**
     * Show a Dialog to the user informing that Fidesmo App must be installed and with option
     * to launch the Google Play store.
     */
    private void promptFidesmoAppInstall() {
        FidesmoInstallDialog mFidesmoInstallDialog = new FidesmoInstallDialog();
        mFidesmoInstallDialog.show(getSupportFragmentManager(), "mFidesmoInstallDialog");
    }

    /**
     * Use the package manager to detect if an application is installed on the phone
     * @param uri an URI identifying the application's package
     * @return 'true' if the app is installed
     */
    private boolean isAndroidAppInstalled(String uri) {
        PackageManager mPackageManager = getPackageManager();
        boolean mAppInstalled = false;
        try {
            mPackageManager.getPackageInfo(uri, PackageManager.GET_ACTIVITIES);
            mAppInstalled = true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(Constants.TAG, "App not installed on Android device");
            mAppInstalled = false;
        }
        return mAppInstalled;
    }
}
