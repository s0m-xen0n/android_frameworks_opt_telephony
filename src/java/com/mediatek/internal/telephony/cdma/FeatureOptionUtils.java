/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.mediatek.internal.telephony.cdma;

import android.os.SystemProperties;

/**
 * The utilities class of CDMA related feature option definitions.
 * @hide
 */
public class FeatureOptionUtils {

    // EVDO dual talk support system property
    public static final String EVDO_DT_SUPPORT = "ril.evdo.dtsupport";
    // SVLTE support system property
    public static final String MTK_SVLTE_SUPPORT = "ro.mtk_svlte_support";
    // IRAT support system property
    public static final String MTK_IRAT_SUPPORT = "ro.c2k.irat.support";
    // MD Based IR support
    public static final String MTK_MD_IRAT_SUPPORT = "ro.c2k.md.irat.support";
    // MTK C2K support
    public static final String MTK_C2K_SUPPORT = "ro.mtk_c2k_support";

    // Feature support.
    public static final String SUPPORT_YES = "1";

    /**
     * Check if EVDO_DT_SUPPORT feature option support is true.
     * @return true if SVLTE is enabled
     */
    public static boolean isEvdoDTSupport() {
        if (SystemProperties.get(EVDO_DT_SUPPORT).equals(SUPPORT_YES)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if CDMA LTE Dual connection(SVLTE) support is true.
     * @return true if SVLTE is enabled
     */
    public static boolean isCdmaLteDcSupport() {
        if (SystemProperties.get(MTK_SVLTE_SUPPORT).equals(SUPPORT_YES)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if CDMA AP IRAT feature is supported.
     * @return True if AP IRAT feature is supported.
     */
    public static boolean isCdmaApIratSupport() {
        return isCdmaIratSupport() && !isCdmaMdIratSupport();
    }

    /**
     * Check if CDMA MD IRAT feature is supported.
     * @return True if MD IRAT feature is supported.
     */
    public static boolean isCdmaMdIratSupport() {
        return SystemProperties.get(MTK_MD_IRAT_SUPPORT).equals(SUPPORT_YES);
    }

    /**
     * Check if CDMA IRAT feature is supported.
     * @return True if C2K IRAT feature is supported.
     */
    public static boolean isCdmaIratSupport() {
        return SystemProperties.get(MTK_IRAT_SUPPORT).equals(SUPPORT_YES);
    }

    /**
     * Check if MTK C2K feature is supported.
     * @return True if MTK C2K feature is supported.
     */
    public static boolean isMtkC2KSupport() {
        return SystemProperties.get(MTK_C2K_SUPPORT).equals(SUPPORT_YES);
    }

    /**
      * Get cdma slot NO.
      * @return static int
      */
    public static int getExternalModemSlot() {
        return SystemProperties.getInt("ril.external.md", 0) - 1;
    }
}
