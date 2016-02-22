package com.mediatek.internal.telephony.ltedc.svlte;

import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.uicc.UiccController;
import com.mediatek.internal.telephony.cdma.FeatureOptionUtils;

/**
 * IRAT utils class.
 * @hide
 */
public class SvlteIratUtils {
    // Attention to not overlap with Google default DctConstants.
    public static final int BASE_IRAT_DATA_CONNECTION = 0x00045000;
    public static final int EVENT_IRAT_DATA_RAT_CHANGED = BASE_IRAT_DATA_CONNECTION + 0;
    public static final int EVENT_LTE_RECORDS_LOADED = BASE_IRAT_DATA_CONNECTION + 1;
    public static final int EVENT_RETRY_SETUP_DATA_FOR_IRAT = BASE_IRAT_DATA_CONNECTION + 2;

    public static final String ACTION_IRAT_PS_TYPE_CHANGED =
            "com.mediatek.action.irat.ps.type.changed";
    public static final String EXTRA_PS_TYPE = "extra_ps_type";

    // PS service is on CDMA or LTE.
    public static final int PS_SERVICE_UNKNOWN = -1;
    public static final int PS_SERVICE_ON_CDMA = 0;
    public static final int PS_SERVICE_ON_LTE = 1;

    // SVLTE phone proxy mode.
    public static final int PHONE_IN_GSM_MODE = 1;
    public static final int PHONE_IN_CDMA_MODE = 2;
    public static final int PHONE_IN_SVLTE_MODE = 3;

    // Set APP family to unknown when radio technology is not specified.
    public static final int APP_FAM_UNKNOWN = 0;

    // Rat group
    public static final int RAT_GROUP_3GPP = 1;
    public static final int RAT_GROUP_3GPP2 = 2;

    private static SvltePhoneProxy sSvltePhoneProxy = null;

    /**
     * Whether IRAT feature is supported.
     * @return True if IRAT is supported, or else false.
     */
    public static boolean isIratSupport() {
        return FeatureOptionUtils.isCdmaIratSupport();
    }

    /**
     * Whether MD IRAT feature is supported.
     * @return True if IRAT is supported, or else false.
     */
    public static boolean isMdIratSupport() {
        return FeatureOptionUtils.isCdmaMdIratSupport();
    }

    /**
     * Whether the phone is IRAT support phone.
     * @param phone Phone instance.
     * @return True if IRAT feature support and the phone ID is major phone(ex:
     *         SIM1).
     */
    public static boolean isIratSupportPhone(Phone phone) {
        final int phoneId = phone.getPhoneId();
        return isIratSupport() && isIratSupportPhone(phoneId);
    }

    /**
     * Whether the specified phone is IRAT supported.
     * @param phoneId Specified phone
     * @return True if IRAT supported.
     */
    public static boolean isIratSupportPhone(int phoneId) {
        return isIratSupport() && (phoneId == PhoneConstants.SIM_ID_1 ||
                phoneId == SubscriptionManager.LTE_DC_PHONE_ID);
    }

    /**
     * Get IRAT support slot ID.
     * @return IRAT support slot ID.
     */
    public static int getIratSupportSlotId() {
        return PhoneConstants.SIM_ID_1;
    }

    /**
     * Get Uicc Family by radio technology.
     * @param radioTech Ratio technology.
     * @return APP family of the RAT.
     */
    public static int getUiccFamilyByRat(int radioTech) {
        if (radioTech == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
            return APP_FAM_UNKNOWN;
        }

        if ((radioTech >= ServiceState.RIL_RADIO_TECHNOLOGY_IS95A
                && radioTech <= ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A)
                || radioTech == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B) {
            return UiccController.APP_FAM_3GPP2;
        } else {
            return UiccController.APP_FAM_3GPP;
        }
    }

    /**
     * There are three types of rat reported from MD: LTE, EHRPD and HRPD. EHRPD
     * and HRPD is in 3GPP2 rat group and LTE is in 3GPP group.
     * @param radioTech RAT for inter 3GPP IRAT
     * @return group for rat
     */
    public static int getRadioGroupByRat(int radioTech) {
        if ((radioTech >= ServiceState.RIL_RADIO_TECHNOLOGY_IS95A
                && radioTech <= ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A)
                || (radioTech >= ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B
                && radioTech <= ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)) {
            return RAT_GROUP_3GPP2;
        } else {
            return RAT_GROUP_3GPP;
        }
    }

    /**
     * Get SVLTE phone proxy.
     * @return SvltePhoneProxy of current IRAT support slot.
     */
    public static SvltePhoneProxy getSvltePhoneProxy() {
        return sSvltePhoneProxy;
    }

    /**
     * Set SVLTE phone proxy after SvltePhoneProxy constructed, avoid
     * sLockProxyPhones lock and sMadeDefaults check.
     * @param svltePhoneProxy SvltePhoneProxy
     */
    public static void setSvltePhoneProxy(SvltePhoneProxy svltePhoneProxy) {
        sSvltePhoneProxy = svltePhoneProxy;
    }
}
