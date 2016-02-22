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

package com.mediatek.internal.telephony.ltedc.svlte;

import android.os.Message;
import android.provider.Settings;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.dataconnection.DcTracker;

import com.mediatek.internal.telephony.cdma.FeatureOptionUtils;
import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController.RoamingMode;
import com.mediatek.internal.telephony.ltedc.svlte.apirat.ApIratController;
import com.mediatek.internal.telephony.ltedc.svlte.apirat.ApIratDataSwitchHelper;
import com.mediatek.internal.telephony.ltedc.svlte.apirat.SvlteIrController;

/**
 * For SVLTE, manage CS/PS phone and a RIL Request/URC arbitrator
 */
public class SvltePhoneProxy extends LteDcPhoneProxy {

    private IratController mIratController;
    private IratDataSwitchHelper mIratDataSwitchHelper;

    private static final int EVENT_RADIO_AVAILABLE = 1000;

    /**
     * Public constructor, pass two phone, one for LTE, one for GSM or CDMA.
     * @param gsmPhone The LTE Phone
     * @param cdmaPhone The non LTE Phone
     */
    public SvltePhoneProxy(PhoneBase gsmPhone, PhoneBase cdmaPhone) {
        super(gsmPhone, cdmaPhone);
        logd("CdmaLteDcPhoneProxy: cdmaPhone = " + cdmaPhone + ", gsmPhone = "
                + gsmPhone);

        setCsPhone(getDefaultCsPhone());
        setPsPhone(getDefaultPsPhone());

        cdmaPhone.mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);

        /// M: Init the SvlteRatController module.
        SvlteRatController.make(this);
        /// M: Init the SvlteRoamingController module.
        if (FeatureOptionUtils.isCdmaApIratSupport()) {
            SvlteIrController.make(this);
        } else {
            SvlteRoamingController.make(this);
        }
        /// M: Init the SvlteSstProxy module.
        SvlteSstProxy.make(this);

        if (FeatureOptionUtils.isCdmaIratSupport()) {
            if (FeatureOptionUtils.isCdmaMdIratSupport()) {
                mIratController = new MdIratController(this);
                mIratDataSwitchHelper = new MdIratDataSwitchHelper(this);
            } else {
                mIratController = new ApIratController(this);
                mIratDataSwitchHelper = new ApIratDataSwitchHelper(this);
            }
        }
        logd("CdmaLteDcPhoneProxy: mIratController = " + mIratController
                + ", mIratDataSwitchHelper = " + mIratDataSwitchHelper
                + ", isCdmaIratSupport() = "
                + FeatureOptionUtils.isCdmaIratSupport()
                + ", isCdmaMdIratSupport() = "
                + FeatureOptionUtils.isCdmaMdIratSupport());
        shareLTEServiceStateTracker(gsmPhone, cdmaPhone);
    }

    @Override
    protected PhoneBase getDefaultCsPhone() {
        return (PhoneBase) mActivePhone;
    }

    @Override
    protected PhoneBase getDefaultPsPhone() {
        SvlteRatController.SvlteRatMode ratMode = getRatMode();
        logd("getDefaultPsPhone: ratMode =" + ratMode);
        if (!ratMode.isCdmaOn()) {
            return mLtePhone;
        } else {
            return (PhoneBase) mActivePhone;
        }
    }

    private void createAndShareDcTracker() {
        mSharedDcTracker = new DcTracker(mPsPhone);
        logd("createAndShareDcTracker: mSharedDcTracker =" + mSharedDcTracker);
        mLtePhone.mDcTracker = mSharedDcTracker;
        mNLtePhone.mDcTracker = mSharedDcTracker;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_RADIO_AVAILABLE:
                SvlteRatController.getInstance().setSvlteRatMode(getRatMode(),
                        RoamingMode.ROAMING_MODE_HOME, null);
                ((PhoneBase) mNLtePhone).mCi.unregisterForAvailable(this);
                break;
            default:
                super.handleMessage(msg);
        }
    }

    /**
     * Get SVLTE RAT mode.
     * @return SVLTE RAT mode.
     */
    public SvlteRatController.SvlteRatMode getRatMode() {
        int ratMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.LTE_ON_CDMA_RAT_MODE,
                SvlteRatController.SvlteRatMode.SVLTE_RAT_MODE_4G.ordinal());
        logd("getRatMode ratMode=" + ratMode);
        return SvlteRatController.SvlteRatMode.values()[ratMode];
    }

    private void shareLTEServiceStateTracker(PhoneBase gsmPhone, PhoneBase cdmaPhone) {
        logd("shareLTEServiceStateTracker: cdmaPhone=" + cdmaPhone
                + ", gsmPhone=" + gsmPhone);
        SvlteServiceStateTracker lteServiceStateTracker = (SvlteServiceStateTracker) cdmaPhone
                .getServiceStateTracker();
        gsmPhone.getServiceStateTracker().setSvlteServiceStateTracker(
                lteServiceStateTracker);
    }

    @Override
    public void dispose() {
        super.dispose();
        logd("dispose: mSharedDcTracker =" + mSharedDcTracker);
        mNLtePhone.mCi.unregisterForAvailable(this);

        mSharedDcTracker.dispose();
        mIratDataSwitchHelper.dispose();
    }

    @Override
    public String getLogTag() {
        return "SvltePhoneProxy";
    }

    /**
     * Do not need phone update in SVLTE case.LTEDcPhone's RAT change to LTE after
     * LTE camp successful, and it will trigger phone object update.
     * But it uses active phone CDMAPhone to update phone object, which causes a C->G phone update.
     * @param voiceRadioTech The new voice radio technology
     */
    @Override
    public void updatePhoneObject(int voiceRadioTech) {
        logd("updatePhoneObject do not need phone update in SVLTE case.");
    }

    @Override
    public void updatePsPhoneAndCi(PhoneBase psPhone) {
        super.updatePsPhoneAndCi(psPhone);
        mIratController.updatePsCi(psPhone.mCi);
        int psType = SvlteIratUtils.PS_SERVICE_UNKNOWN;
        if (psPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            psType = SvlteIratUtils.PS_SERVICE_ON_LTE;
        } else if (psPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            psType = SvlteIratUtils.PS_SERVICE_ON_CDMA;
        }
        logd("updatePsPhoneAndCi: psType = " + psType);
        mIratDataSwitchHelper.setPsServiceType(psType);
    }

    @Override
    public void initialize() {
        SvlteIratUtils.setSvltePhoneProxy(this);

        // NOTE: Create DcTracker after set SVLTE phone proxy.
        createAndShareDcTracker();
        mIratController.setDcTracker(mSharedDcTracker);
        updateDefaultPsPhone();
    }

    /**
     * Get IRAT data swtich helper of the phone proxy.
     * @return IRAT data swtich helper of the phone proxy.
     */
    public IratDataSwitchHelper getIratDataSwitchHelper() {
        return mIratDataSwitchHelper;
    }

    /**
     * Get IRAT controller of the phone proxy.
     * @return IRAT controller of the phone proxy.
     */
    public IratController getIratController() {
        return mIratController;
    }

    private void updateDefaultPsPhone() {
        PhoneBase psPhone = getDefaultPsPhone();
        int psType = SvlteIratUtils.PS_SERVICE_UNKNOWN;
        if (psPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            psType = SvlteIratUtils.PS_SERVICE_ON_LTE;
        } else if (psPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            psType = SvlteIratUtils.PS_SERVICE_ON_CDMA;
        }
        updatePsPhoneAndCi(psPhone);
        mIratDataSwitchHelper.setPsServiceType(psType);
        mIratController.setInitialPsType(psType);
    }
}
