/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mediatek.internal.telephony.uicc;

import android.os.SystemProperties;
import android.telephony.Rlog;

import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.mediatek.internal.telephony.cdma.FeatureOptionUtils;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Provide SVLTE UICC card/application related utilities.
 */

public class SvlteUiccUtils {

    private static final boolean DBG = true;
    private static final String LOG_TAG = "SvlteUiccUtils";

    private static final String[] UICCCARD_PROPERTY_RIL_UICC_TYPE = {
        "gsm.ril.uicctype",
        "gsm.ril.uicctype.2",
        "gsm.ril.uicctype.3",
        "gsm.ril.uicctype.4",
    };
    private static final String[] PROPERTY_RIL_FULL_UICC_TYPE = {
        "gsm.ril.fulluicctype",
        "gsm.ril.fulluicctype.2",
        "gsm.ril.fulluicctype.3",
        "gsm.ril.fulluicctype.4",
    };

    private SvlteUiccUtils() {
    }

    /**
     * Singleton to get SvlteUiccApplicationUpdateStrategy instance.
     *
     * @return SvlteUiccApplicationUpdateStrategy instance
     */
    public static synchronized SvlteUiccUtils getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * Check if need to update application type.
     *
     * @param appType for current application type
     * @return true if need to update application type
     */
    public boolean isNeedToAdjustAppType(int appType) {
        return (FeatureOptionUtils.isCdmaLteDcSupport()
                && (UiccController.APP_FAM_IMS != appType));
    }

    /**
     * To adjust application type. Rules: (USIM+CSIM) or (USIM+SIM): SIM PIN by MD1 (RUIM+SIM) or
     * (RUIM/CSIM ONLY): SIM PIN by MD3
     *
     * @param uiccCard for current UICC card type
     * @param appType for current application type
     * @return adjusted application type
     */
    public int adjustAppType(UiccCard uiccCard, int appType) {
        if (DBG) {
            logd("appType: " + appType);
        }
        if (isUsimSim(uiccCard)) {
            return UiccController.APP_FAM_3GPP;
        } else {
            return UiccController.APP_FAM_3GPP2;
        }
    }

    /**
     * Check if it is USIM/SIM.
     *
     * @param uiccCard for current UICC card type
     * @return true if it is USIM/(SIM ONLY)
     */
    public boolean isUsimSim(UiccCard uiccCard) {
        HashSet<String> fullUiccType = new HashSet<String>(
                Arrays.asList(uiccCard.getFullIccCardType()));
        return (fullUiccType.contains("USIM")
                || (uiccCard.getIccCardType().equals("SIM")));
    }

    /**
     * Check if it is USIM and CSIM.
     *
     * @param uiccCard for current UICC card type
     * @return true if it is USIM and CSIM
     */
    public boolean isUsimWithCsim(UiccCard uiccCard) {
        HashSet<String> fullUiccType = new HashSet<String>(
                Arrays.asList(uiccCard.getFullIccCardType()));
        return (fullUiccType.contains("USIM")
                && (fullUiccType.contains("CSIM")));
    }

    /**
     * Check if it is UIM or CSIM.
     *
     * @param uiccCard for current UICC card type
     * @return true if it is UIM or CSIM
     */
    public boolean isRuimCsim(UiccCard uiccCard) {
        return (uiccCard.getIccCardType().equals("RUIM")
                || (uiccCard.getIccCardType().equals("CSIM")));
    }

    /**
     * Check if it is USIM and CSIM.
     *
     * @param slotId for current slot
     * @return true if it is USIM and CSIM
     */
    public boolean isUsimWithCsim(int slotId) {
        HashSet<String> fullUiccType = new HashSet<String>(
                Arrays.asList(getFullIccCardType(slotId)));
        return (fullUiccType.contains("USIM")
                && (fullUiccType.contains("CSIM")));
    }

    /**
     * Check if it is UIM or CSIM.
     *
     * @param slotId for current slot
     * @return true if it is UIM or CSIM
     */
    public boolean isRuimCsim(int slotId) {
        return (getIccCardType(slotId).equals("RUIM")
                || (getIccCardType(slotId).equals("CSIM")));
    }

    /**
     * Create SvlteUiccApplicationUpdateStrategy instance.
     *
     * @hide
     */
    private static class SingletonHolder {
        public static final SvlteUiccUtils INSTANCE =
                new SvlteUiccUtils();
    }

    /**
     * To get ICC Card type by slot ID.
     * To use it when UiccCard is not ready
     */
    private String getIccCardType(int slotId) {
        return SystemProperties.get(UICCCARD_PROPERTY_RIL_UICC_TYPE[slotId]);
    }

    /**
     * To get ICC FULL Card type by slot ID.
     * To use it when UiccCard is not ready
     */
    private String[] getFullIccCardType(int slotId) {
        return SystemProperties.get(PROPERTY_RIL_FULL_UICC_TYPE[slotId]).split(",");
    }

    /**
     * Log level.
     *
     * @hide
     */
    private void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

}
