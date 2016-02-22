/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 *
 */

package com.mediatek.internal.telephony.ltedc;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.Phone.DataActivityState;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.dataconnection.DcFailCause;
import com.android.internal.telephony.dataconnection.DcTracker;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRilArbitrator;

/**
 * For SGLTE/SVLTE, manage CS/PS phone and a RIL Request/URC arbitrator
 */
public class LteDcPhoneProxy extends PhoneProxy {

    private static final String LOG_TAG = "PHONE";

    protected Context mContext;
    protected IRilDcArbitrator mRilDcArbitrator;

    protected PhoneBase mLtePhone;
    protected PhoneBase mNLtePhone;
    protected PhoneBase mPsPhone;
    protected PhoneBase mCsPhone;

    protected DcTracker mSharedDcTracker;

    /**
     * Public constructor, pass two phone, one for LTE, one for GSM or CDMA.
     * @param ltePhone The LTE Phone
     * @param nltePhone The non LTE Phone
     */
    public LteDcPhoneProxy(PhoneBase ltePhone, PhoneBase nltePhone) {
        super(nltePhone);
        mLtePhone = ltePhone;
        mNLtePhone = nltePhone;
        mContext = mNLtePhone.getContext();

        mCsPhone = getDefaultCsPhone();
        mPsPhone = getDefaultPsPhone();
        mRilDcArbitrator = new SvlteRilArbitrator(mLtePhone, mNLtePhone);

        logd("LteDcPhoneProxy: mLtePhone = " + mLtePhone + ", mNLtePhone = "
                + mNLtePhone);
    }

    @Override
    public void dispose() {
        if (mLtePhone != null) {
            mLtePhone.dispose();
        }
        if (mNLtePhone != null) {
            mNLtePhone.dispose();
        }
    }

    @Override
    public void removeReferences() {
        logd("removeReferences: mLtePhone = " + mLtePhone + ", mNLtePhone = "
                + mNLtePhone);
        if (mLtePhone != null) {
            mLtePhone.removeReferences();
        }
        if (mNLtePhone != null) {
            mNLtePhone.removeReferences();
        }
    }

    /**
     * Initialize params and components, avoid cycle reference in
     * PhoneFactory.getPhone().
     */
    public void initialize() {

    }

    /**
     * Get the PS Phone.
     * @return The PS Phone
     */
    public PhoneBase getPsPhone() {
        return mPsPhone;
    }

    /**
     * Get the CS Phone.
     * @return The CS Phone
     */
    public Phone getCsPhone() {
        return mCsPhone;
    }

    /**
     * Set the PS Phone.
     * @param psPhone The PS Phone to set
     */
    public void setPsPhone(PhoneBase psPhone) {
        mPsPhone = psPhone;
    }

    /**
     * Set the CS Phone.
     * @param csPhone The CS Phone to set
     */
    public void setCsPhone(PhoneBase csPhone) {
        mCsPhone = csPhone;
    }

    /**
     * Get the PS Phone.
     * @return The PS Phone
     */
    public PhoneBase getLtePhone() {
        return mLtePhone;
    }

    /**
     * Get the PS Phone.
     * @return The PS Phone
     */
    public PhoneBase getNLtePhone() {
        return mNLtePhone;
    }

    /**
     * Set the LTE Phone.
     * @param ltePhone The LTE Phone to set
     */
    public void setLtePhone(PhoneBase ltePhone) {
        mLtePhone = ltePhone;
    }

    /**
     * Set the non LTE Phone.
     * @param nltePhone The non LTE Phone to set
     */
    public void setNLtePhone(PhoneBase nltePhone) {
        mNLtePhone = nltePhone;
    }

    protected PhoneBase getDefaultCsPhone() {
        return mNLtePhone;
    }

    protected PhoneBase getDefaultPsPhone() {
        return mLtePhone;
    }

    /**
     * Update PS phone when data rat changed.
     * @param sourceRat source data rat
     * @param targetRat target data rat
     */
    public void updatePsPhone(int sourceRat, int targetRat) {
        log("updatePsPhone, sourceRat=" + sourceRat + ", targetRat="
                + targetRat);

        switch (targetRat) {
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE:
                log("updatePsPhone to ltePhone");
                mPsPhone = mLtePhone;
                break;

            case ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD:
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B:
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A:
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0:
            case ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT:
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95A:
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95B:
                log("updatePsPhone to nltePhone");
                mPsPhone = mNLtePhone;
                break;

            default:
                log("updatePsPhone, target rat is unknown, to ltePhone");
                mPsPhone = mLtePhone;
                break;
        }

        // update ps Ril
        updatePsPhoneAndCi(mPsPhone);
    }

    /**
     * Update PS phone and RIL in IRAT project.
     * @param psPhone PS phone.
     */
    public void updatePsPhoneAndCi(PhoneBase psPhone) {
        log("updatePsPhoneAndCi: psPhone = " + psPhone);
        mPsPhone = psPhone;
        mRilDcArbitrator.updatePsCi(((PhoneBase) mPsPhone).mCi);
    }

    /**
     * Get shared DcTracker.
     * @return Shared DcTracker.
     */
    public DcTracker getSharedDcTracker() {
        return mSharedDcTracker;
    }

    /**
     * Set Radio power for SVLTE LTEDcPhone.
     * @param power desired radio power
     * @param phoneId The id of the phone to set
     */
    public void setRadioPower(boolean power, int phoneId) {
        log("setRadioPower phoneId=" + phoneId + " power=" + power);
        if (getPhoneById(phoneId) != null) {
            getPhoneById(phoneId).setRadioPower(power);
        }
    }

    /**
     * Get Phone using phone id.
     * @param phoneId The id to acces Phone.
     * @return The specified phone.
     */
    public Phone getPhoneById(int phoneId) {
        if (phoneId == mNLtePhone.getPhoneId()) {
            return mNLtePhone;
        } else if (phoneId == mLtePhone.getPhoneId()) {
            return mLtePhone;
        } else {
            log("getPhoneById should come here");
            return null;
        }
    }

    /**
     * C2K SVLTE remote SIM access.
     * @param modemStatus The Modem status: 0: Only MD1 active
     *                                      1: MD1's RF is closed, but MD1's SIM task is still
     *                                         working onlyfor MD3 SIM remove access and MD3 active
     *                                      2: Both MD1 and MD3 active
     * @param remoteSimProtocol MD3 decide to access SIM from which protocl of MD1
     *                          0: MD3 access local card
     *                          1: MD1 access MD1's SIM task1
     *                          2: MD1 access MD1's SIM task2
     * @param type The phone type of target Phone
     * @param result callback message
     */
    public void configModemStatus(int modemStatus, int remoteSimProtocol,
            int type, Message result) {
        log("configModemStatus phoneType=" + type + " modemStatus="
                + modemStatus + " remoteSimProtocol=" + remoteSimProtocol);

        if (type == PhoneConstants.PHONE_TYPE_CDMA && mNLtePhone != null) {
            ((PhoneBase) mNLtePhone).mCi.configModemStatus(modemStatus,
                    remoteSimProtocol, result);
        } else if (type == PhoneConstants.PHONE_TYPE_GSM && mLtePhone != null) {
            ((PhoneBase) mLtePhone).mCi.configModemStatus(modemStatus,
                    remoteSimProtocol, result);
        } else {
            log("configModemStatus invalid phoneType!");
        }
    }

    /**
     * Switch active phone. For SVLTE, CDMAPhone is always the active Phone, but
     * if we enter LTE only mode, the active Phone should change to LTEDcPhone.
     * @param lteMode Whether switch active phone to LTE or Non-LTE phone.
     */
    public void toggleActivePhone(boolean lteMode) {
        final Phone activePhone = getActivePhone();
        if (activePhone == null || (lteMode && activePhone.equals(mLtePhone))
                || (!lteMode && activePhone.equals(mNLtePhone))) {
            log("switchActivePhone return without action, lteMode = " + lteMode
                    + ", activePhone = " + activePhone + ", mLtePhone = "
                    + mLtePhone + ", mNLtePhone = " + mNLtePhone);
            return;
        }
        switchActivePhone(lteMode ? (PhoneBase) mLtePhone
                : (PhoneBase) mNLtePhone);
    }

    private void switchActivePhone(Phone targetPhone) {
        logd("switchActivePhone targetPhone=" + targetPhone + ", oldPhone="
                + mActivePhone);
        Phone oldPhone = mActivePhone;
        mActivePhone = targetPhone;

        // Update ActivePhone for CallManager
        CallManager.getInstance().registerPhone(mActivePhone);
        CallManager.getInstance().unregisterPhone(oldPhone);

        updatePhoneIds(oldPhone, mActivePhone);

        // Set the new interfaces in the proxy's
        mIccSmsInterfaceManager.updatePhoneObject((PhoneBase) mActivePhone);
        mIccPhoneBookInterfaceManagerProxy
                .setmIccPhoneBookInterfaceManager(mActivePhone
                        .getIccPhoneBookInterfaceManager());
        mPhoneSubInfoProxy.setmPhoneSubInfo(mActivePhone.getPhoneSubInfo());

        mCommandsInterface = ((PhoneBase) mActivePhone).mCi;
        mIccCardProxy
                .setVoiceRadioTech(mActivePhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ? ServiceState.RIL_RADIO_TECHNOLOGY_GSM
                        : ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A);

        // Update PS Phone
        int oldSs = mActivePhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ? ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A
                : ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
        int newSs = mActivePhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ? ServiceState.RIL_RADIO_TECHNOLOGY_LTE
                : ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A;
        updatePsPhone(oldSs, newSs);

        // Send an Intent to the PhoneApp that we had a radio technology change
        Intent intent = new Intent(
                TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.PHONE_NAME_KEY,
                mActivePhone.getPhoneName());
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, getPhoneId());
        ActivityManagerNative.broadcastStickyIntent(intent, null,
                UserHandle.USER_ALL);
    }

    private void updatePhoneIds(Phone oldPhone, Phone newPhone) {
        // Update phone id, just switch between old and new
        int oldPhoneId = oldPhone.getPhoneId();
        int newPhoneId = mActivePhone.getPhoneId();
        oldPhone.setPhoneId(newPhoneId);
        mActivePhone.setPhoneId(oldPhoneId);
    }

    @Override
    public void getDataCallList(Message response) {
        mPsPhone.getDataCallList(response);
    }

    @Override
    public boolean getDataRoamingEnabled() {
        return mPsPhone.getDataRoamingEnabled();
    }

    @Override
    public void setDataRoamingEnabled(boolean enable) {
        mPsPhone.setDataRoamingEnabled(enable);
    }

    @Override
    public boolean getDataEnabled() {
        return mPsPhone.getDataEnabled();
    }

    @Override
    public void setDataEnabled(boolean enable) {
        mPsPhone.setDataEnabled(enable);
    }

    @Override
    public boolean isDataConnectivityPossible() {
        return mPsPhone
                .isDataConnectivityPossible(PhoneConstants.APN_TYPE_DEFAULT);
    }

    @Override
    public boolean isDataConnectivityPossible(String apnType) {
        return mPsPhone.isDataConnectivityPossible(apnType);
    }

    /*
    public DcFailCause getLastDataConnectionFailCause(String apnType) {
        return mPsPhone.getLastDataConnectionFailCause(apnType);
    }
    */

    @Override
    public PhoneConstants.DataState getDataConnectionState() {
        return mPsPhone.getDataConnectionState(PhoneConstants.APN_TYPE_DEFAULT);
    }

    @Override
    public PhoneConstants.DataState getDataConnectionState(String apnType) {
        return mPsPhone.getDataConnectionState(apnType);
    }

    @Override
    public DataActivityState getDataActivityState() {
        return mPsPhone.getDataActivityState();
    }

    /**
     * Return the RilArbitrator.
     * @return IRilDcArbitrator
     */
    public IRilDcArbitrator getRilDcArbitrator() {
        return mRilDcArbitrator;
    }

    /**
     * To override log format, add LteDcPhoneProxy prefix.
     * @param msg The log to print
     */
    public void log(String msg) {
        Rlog.i(LOG_TAG, "[" + getLogTag() + "] " + msg);
    }

    /**
     * To override log format, add LteDcPhoneProxy prefix.
     * @param msg The log to print
     */
    public void logv(String msg) {
        Rlog.v(LOG_TAG, "[" + getLogTag() + "] " + msg);
    }

    /**
     * To override log format, add LteDcPhoneProxy prefix.
     * @param msg The log to print
     */
    public void logd(String msg) {
        Rlog.d(LOG_TAG, "[" + getLogTag() + "] " + msg);
    }

    /**
     * To override log format, add LteDcPhoneProxy prefix.
     * @param msg The log to print
     */
    public void logw(String msg) {
        Rlog.w(LOG_TAG, "[" + getLogTag() + "] " + msg);
    }

    /**
     * To override log format, add LteDcPhoneProxy prefix.
     * @param msg The log to print
     */
    public void loge(String msg) {
        Rlog.e(LOG_TAG, "[" + getLogTag() + "] " + msg);
    }

    /**
     * To override log format, add prefix.
     * @return The prefix
     */
    protected String getLogTag() {
        return "IRAT_LteDcPhoneProxy";
    }
}
