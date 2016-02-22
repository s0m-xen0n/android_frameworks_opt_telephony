package com.mediatek.internal.telephony.ltedc.svlte;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.CellInfoCdma;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.cdma.CdmaCellLocation;
import android.util.EventLog;

import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.cdma.CdmaServiceStateTracker;

import com.mediatek.internal.telephony.cdma.FeatureOptionUtils;

/**
 * Add for SVLTE CDMA ServiceStateTracker to notify the gsm and cdma related state change.
 */
public class SvlteServiceStateTracker extends CdmaServiceStateTracker {
    private static final String LOG_TAG = "SvlteSST";
    private static final boolean DBG = true;
    private SignalStrength mGSMSignalStrength = new SignalStrength();
    private SignalStrength mCDMASignalStrength = new SignalStrength(false);
    private SignalStrength mCombinedSignalStrength = new SignalStrength(false);
    private ServiceState mGSMSS = new ServiceState();
    private ServiceState mCDMASS = new ServiceState();
    private ServiceState mCombinedSS = new ServiceState();
    private int mPsType = SvlteIratUtils.PS_SERVICE_UNKNOWN;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            log("received broadcast, action = " + action);
            if (SvlteIratUtils.ACTION_IRAT_PS_TYPE_CHANGED.equals(action)) {
                mPsType = intent.getIntExtra(SvlteIratUtils.EXTRA_PS_TYPE,
                        SvlteIratUtils.PS_SERVICE_UNKNOWN);
                log("received broadcast, action = " + action + " mPsType = " + mPsType);
                if (mPsType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
                    mCombinedSS.setRilDataRadioTechnology(mCDMASS
                            .getRilDataRadioTechnology());
                } else if (mPsType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
                    mCombinedSS.setRilDataRadioTechnology(mGSMSS
                            .getRilDataRadioTechnology());
                }
                mPhone.notifyServiceStateChangedForSvlte(mCombinedSS);
            }
        }
    };
/**
 * The Service State Tracker for SVLTE.
 * @param phone The CDMAPhone to create the Servie State Tracker.
 */
    public SvlteServiceStateTracker(CDMAPhone phone) {
        super(phone, new CellInfoCdma());
        final IntentFilter filter = new IntentFilter();
        filter.addAction(SvlteIratUtils.ACTION_IRAT_PS_TYPE_CHANGED);
        phone.getContext().registerReceiver(mReceiver, filter);
    }

    /**
     * send gsm signal-strength-changed notification if changed Called both for
     * solicited and unsolicited signal strength updates.
     * @param gsmSignalStrength The gsm Signal Strength
     * @return true if the gsm signal strength changed and a notification was
     *         sent.
     */
    public boolean onGSMSignalStrengthResult(SignalStrength gsmSignalStrength) {
        log("onGSMSignalStrengthResult(): gsmSignalStrength = "
                + gsmSignalStrength.toString());
        mGSMSignalStrength = new SignalStrength(gsmSignalStrength);
        combineGsmCdmaSignalStrength();
        return notifySignalStrength();
    }

    /**
     * send cdma signal-strength-changed notification if changed Called both for
     * solicited and unsolicited signal strength updates.
     * @param cdmaSignalStrength The cdma Signal Strength
     * @return true if the cdma signal strength changed and a notification was
     *         sent.
     */
    public boolean onCDMASignalStrengthResult(SignalStrength cdmaSignalStrength) {
        log("onCDMASignalStrengthResult(): cdmaSignalStrength = "
                + cdmaSignalStrength.toString());
        mCDMASignalStrength = new SignalStrength(cdmaSignalStrength);
        combineGsmCdmaSignalStrength();
        return notifySignalStrength();
    }

    protected SignalStrength mLastCombinedSignalStrength = null;

    protected boolean notifySignalStrength() {
        boolean notified = false;
        synchronized (mCellInfo) {
            if (!mCombinedSignalStrength.equals(mLastCombinedSignalStrength)) {
                try {
                    if (DBG) {
                        log("notifySignalStrength: mCombinedSignalStrength.getLevel="
                                + mCombinedSignalStrength.getLevel());
                    }
                    mPhone.notifySignalStrength();
                    mLastCombinedSignalStrength = new SignalStrength(
                            mCombinedSignalStrength);
                    notified = true;
                } catch (NullPointerException ex) {
                    loge("updateSignalStrength() Phone already destroyed: "
                            + ex + "SignalStrength not notified");
                }
            }
        }
        return notified;
    }

    private void combineGsmCdmaSignalStrength() {
        if (DBG) {
            log("combineGsmCdmaSignalStrength: mGSMSignalStrength= "
                    + mGSMSignalStrength + "mCDMASignalStrength = "
                    + mCDMASignalStrength);
        }
        mCombinedSignalStrength.setGsmSignalStrength(mGSMSignalStrength
                .getGsmSignalStrength());
        mCombinedSignalStrength.setGsmBitErrorRate(mGSMSignalStrength
                .getGsmBitErrorRate());
        mCombinedSignalStrength.setLteSignalStrength(mGSMSignalStrength
                .getLteSignalStrength());
        mCombinedSignalStrength.setLteRsrp(mGSMSignalStrength.getLteRsrp());
        mCombinedSignalStrength.setLteRsrq(mGSMSignalStrength.getLteRsrq());
        mCombinedSignalStrength.setLteRssnr(mGSMSignalStrength.getLteRssnr());
        mCombinedSignalStrength.setLteCqi(mGSMSignalStrength.getLteCqi());
        mCombinedSignalStrength.setGsmRssiQdbm(mGSMSignalStrength.getGsmRssiQdbm());
        mCombinedSignalStrength.setGsmRscpQdbm(mGSMSignalStrength.getGsmRscpQdbm());
        mCombinedSignalStrength.setGsmEcn0Qdbm(mGSMSignalStrength.getGsmEcn0Qdbm());
        mCombinedSignalStrength.setEvdoDbm(mCDMASignalStrength.getEvdoDbm());
        mCombinedSignalStrength.setEvdoEcio(mCDMASignalStrength.getEvdoEcio());
        mCombinedSignalStrength.setEvdoSnr(mCDMASignalStrength.getEvdoSnr());
        mCombinedSignalStrength.setCdmaDbm(mCDMASignalStrength.getCdmaDbm());
        mCombinedSignalStrength.setCdmaEcio(mCDMASignalStrength.getCdmaEcio());
        if (DBG) {
            log("combineGsmCdmaSignalStrength: mCombinedSignalStrength= "
                    + mCombinedSignalStrength);
        }
    }

    /**
     * @return signal strength
     */
    public SignalStrength getSignalStrength() {
        synchronized (mCellInfo) {
            return mCombinedSignalStrength;
        }
    }

    @Override
    public void handleMessage(Message msg) {

        AsyncResult ar;
        switch (msg.what) {
        case EVENT_SIGNAL_STRENGTH_UPDATE:
            // This is a notification from
            // CommandsInterface.setOnSignalStrengthUpdate.

            ar = (AsyncResult) msg.obj;
            log("EVENT_SIGNAL_STRENGTH_UPDATE, ar = " + ar.result);

            // The radio is telling us about signal strength changes,
            // so we don't have to ask it.
            mDontPollSignalStrength = true;
            setSignalStrength(ar, false);
            onCDMASignalStrengthResult(mSignalStrength);
            break;

        case EVENT_GET_SIGNAL_STRENGTH:
            // This callback is called when signal strength is polled
            // all by itself

            if (!(mCi.getRadioState().isOn())) {
                // Polling will continue when radio turns back on
                return;
            }
            ar = (AsyncResult) msg.obj;
            log("EVENT_GET_SIGNAL_STRENGTH, ar = " + ar.result);
            setSignalStrength(ar, false);
            onCDMASignalStrengthResult(mSignalStrength);
            queueNextSignalStrengthPoll();
            break;

        default:
            super.handleMessage(msg);
            break;
        }
    }

    @Override
    protected void pollStateDone() {
        if (DBG) {
            log(this + "pollStateDone: cdma oldSS=[" + mSS + "] newSS=["
                    + mNewSS + "]");
        }

        if (Build.IS_DEBUGGABLE
                && SystemProperties.getBoolean(PROP_FORCE_ROAMING, false)) {
            mNewSS.setRoaming(true);
        }

        useDataRegStateForDataOnlyDevices();

        boolean hasRegistered = mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered = mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
                mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionDetached =
                mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionChanged = mSS.getDataRegState() != mNewSS
                .getDataRegState();

        boolean hasRilVoiceRadioTechnologyChanged = mSS
                .getRilVoiceRadioTechnology() != mNewSS
                .getRilVoiceRadioTechnology();

        boolean hasRilDataRadioTechnologyChanged = mSS
                .getRilDataRadioTechnology() != mNewSS
                .getRilDataRadioTechnology();

        boolean hasChanged = !mNewSS.equals(mSS);

        // AOSP/CM: voice and data roaming is separately considered
        boolean hasVoiceRoamingOn = !mSS.getVoiceRoaming() && mNewSS.getVoiceRoaming();

        boolean hasVoiceRoamingOff = mSS.getVoiceRoaming() && !mNewSS.getVoiceRoaming();

        boolean hasDataRoamingOn = !mSS.getDataRoaming() && mNewSS.getDataRoaming();

        boolean hasDataRoamingOff = mSS.getDataRoaming() && !mNewSS.getDataRoaming();

        boolean hasLocationChanged = !mNewCellLoc.equals(mCellLoc);

        // / M: c2k modify. @{
        boolean hasRegStateChanged = mSS.getRegState() != mNewSS.getRegState();
        log("pollStateDone: hasRegStateChanged = " + hasRegStateChanged);
        // / @}

        // Add an event log when connection state changes
        if (mSS.getVoiceRegState() != mNewSS.getVoiceRegState()
                || mSS.getDataRegState() != mNewSS.getDataRegState()) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE,
                    mSS.getVoiceRegState(), mSS.getDataRegState(),
                    mNewSS.getVoiceRegState(), mNewSS.getDataRegState());
        }

        // / M: c2k modify. @{
        if (mNewSS.getState() == ServiceState.STATE_IN_SERVICE) {
            mInService = true;
        } else {
            mInService = false;
        }
        log("pollStateDone: mInService = " + mInService);
        // / @}

        ServiceState tss;
        tss = mSS;
        mSS = mNewSS;
        mNewSS = tss;
        // clean slate for next time
        mNewSS.setStateOutOfService();

        CdmaCellLocation tcl = mCellLoc;
        mCellLoc = mNewCellLoc;
        mNewCellLoc = tcl;

        if (hasRilVoiceRadioTechnologyChanged) {
            updatePhoneObject();
        }

        if (hasRilDataRadioTechnologyChanged) {
            // / M: c2k modify. @{
            // query network time if Network Type is changed to a valid state
            if (mNewNetworkType != 0) {
                queryCurrentNitzTime();
            }
            // / @}
            mPhone.setSystemProperty(
                    TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.rilRadioTechnologyToString(mSS
                            .getRilDataRadioTechnology()));
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if ((mCi.getRadioState().isOn()) && (!mIsSubscriptionFromRuim)) {
                log("pollStateDone isSubscriptionFromRuim = "
                        + mIsSubscriptionFromRuim);
                String eriText;
                // Now the CDMAPhone sees the new ServiceState so it can get the
                // new ERI text
                if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = mPhone.getCdmaEriText();
                } else {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used
                    // for
                    // mRegistrationState 0,2,3 and 4
                    eriText = mPhone
                            .getContext()
                            .getText(
                                    com.android.internal.R.string.roamingTextSearching)
                            .toString();
                }
                mSS.setOperatorAlphaLong(eriText);
            }

            String operatorNumeric;

            mPhone.setSystemProperty(
                    TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                    mSS.getOperatorAlphaLong());

            String prevOperatorNumeric = SystemProperties.get(
                    TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, "");
            operatorNumeric = mSS.getOperatorNumeric();

            // try to fix the invalid Operator Numeric
            if (isInvalidOperatorNumeric(operatorNumeric)) {
                int sid = mSS.getSystemId();
                operatorNumeric = fixUnknownMcc(operatorNumeric, sid);
            }

            mPhone.setSystemProperty(
                    TelephonyProperties.PROPERTY_OPERATOR_NUMERIC,
                    operatorNumeric);
            updateCarrierMccMncConfiguration(operatorNumeric,
                    prevOperatorNumeric, mPhone.getContext());

            if (isInvalidOperatorNumeric(operatorNumeric)) {
                if (DBG) {
                    log("operatorNumeric " + operatorNumeric + "is invalid");
                }
                mPhone.setSystemProperty(
                        TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
                mGotCountryCode = false;
            } else {
                String isoCountryCode = "";
                String mcc = operatorNumeric.substring(0, 3);
                try {
                    isoCountryCode = MccTable.countryCodeForMcc(Integer
                            .parseInt(operatorNumeric.substring(0, 3)));
                } catch (NumberFormatException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                } catch (StringIndexOutOfBoundsException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                }

                mPhone.setSystemProperty(
                        TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY,
                        isoCountryCode);
                mGotCountryCode = true;

                setOperatorIdd(operatorNumeric);

                if (shouldFixTimeZoneNow(mPhone, operatorNumeric,
                        prevOperatorNumeric, mNeedFixZone)) {
                    fixTimeZone(isoCountryCode);
                }
            }

            mPhone.setSystemProperty(
                    TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                    mSS.getRoaming() ? "true" : "false");

            updateSpnDisplay();
            // / M: c2k modify. @{
            if (hasRegStateChanged) {
                if (mSS.getRegState() == ServiceState.REGISTRATION_STATE_UNKNOWN
                        && (1 == Settings.System.getInt(mPhone.getContext()
                                .getContentResolver(),
                                Settings.System.AIRPLANE_MODE_ON, -1))) {
                    int serviceState = mPhone.getServiceState().getState();
                    if (serviceState != ServiceState.STATE_POWER_OFF) {
                        mSS.setStateOff();
                    }
                }
            }
            // / @}
            mCDMASS = new ServiceState(mSS);
            mPhone.notifyServiceStateChangedPForRegistrants(mCDMASS);
            combineGsmCdmaServiceState();
            mPhone.notifyServiceStateChangedForSvlte(mCombinedSS);
        }

        if (hasCdmaDataConnectionAttached) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionChanged || hasRilDataRadioTechnologyChanged) {
            notifyDataRegStateRilRadioTechnologyChanged();
            mPhone.notifyDataConnection(null);
        }

        // CM/AOSP
        if (hasVoiceRoamingOn) {
            mVoiceRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasVoiceRoamingOff) {
            mVoiceRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasDataRoamingOn) {
            mDataRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasDataRoamingOff) {
            mDataRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            mPhone.notifyLocationChanged();
        }
        // TODO: Add CdmaCellIdenity updating, see CdmaLteServiceStateTracker.
    }
/**
 * Notify the Service State Changed for SVLTE.
 * @param ss The Service State will be notified.
 */
    public void notifyServiceStateChanged(ServiceState ss) {
        mGSMSS = new ServiceState(ss);
        combineGsmCdmaServiceState();
        mPhone.notifyServiceStateChangedForSvlte(mCombinedSS);
    }

    public int getPhoneId() {
        return mPhone.getPhoneId();
    }


    //parameter: sst and it's voice & data service state
    public boolean needUpdateSvlteSpn(int voiceState, int dataState, boolean isCdmaSst) {
        if (DBG) {
            log("needUpdateSvlteSpn, voiceState = " + voiceState + ",dataState = "
                    + dataState +",isCdmaSst = " + isCdmaSst);
        }
        if (!isCdmaSst) { //lte sst
            // lteSST update spn when cdma is oos both for data/voice
            if ((this.mCDMASS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE &&
                this.mCDMASS.getDataRegState() != ServiceState.STATE_IN_SERVICE) &&
                (voiceState == ServiceState.STATE_IN_SERVICE ||
                dataState == ServiceState.STATE_IN_SERVICE)) {
                return true;
            }
            return false;
        } else { //cdma sst
            if ((voiceState != ServiceState.STATE_IN_SERVICE &&
                dataState != ServiceState.STATE_IN_SERVICE) &&
                (this.mGSMSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE ||
                this.mGSMSS.getDataRegState() == ServiceState.STATE_IN_SERVICE)) {
                return false;
            }
            return true;
        }
    }

    private void combineGsmCdmaServiceState() {
        if (DBG) {
            log("combineGsmCdmaServiceState, mCDMASS " + mCDMASS + " mGSMSS ="
                    + mGSMSS);
        }
        //mCombinedSS = new ServiceState(mSS);
        if (mGSMSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
            mCombinedSS = new ServiceState(mGSMSS);
        } else {
            mCombinedSS = new ServiceState(mCDMASS);
        }
        if (mGSMSS.getDataRegState() == ServiceState.STATE_IN_SERVICE) {
            mCombinedSS.setDataRegState(mGSMSS.getDataRegState());
            mCombinedSS.setRilDataRadioTechnology(mGSMSS
                    .getRilDataRadioTechnology());
            mCombinedSS.setRilDataRegState(mGSMSS.getRilDataRegState());
        } else {
            mCombinedSS.setDataRegState(mCDMASS.getDataRegState());
            mCombinedSS.setRilDataRadioTechnology(mCDMASS
                    .getRilDataRadioTechnology());
            mCombinedSS.setRilDataRegState(mCDMASS.getRilDataRegState());
        }
        ///Add for AP-RAT. @{
        if (FeatureOptionUtils.isCdmaIratSupport()) {
            if (!FeatureOptionUtils.isCdmaMdIratSupport()) {
                if (mPsType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
                    mCombinedSS.setRilDataRadioTechnology(mCDMASS
                            .getRilDataRadioTechnology());
                } else if (mPsType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
                    mCombinedSS.setRilDataRadioTechnology(mGSMSS
                            .getRilDataRadioTechnology());
                }
            }
        }
        /// @}
      mPhone.notifySvlteServiceStateChangedPForRegistrants(mCombinedSS);
        if (DBG) {
            log("combineGsmCdmaServiceState, mCombinedSS = " + mCombinedSS);
        }
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[SvlteSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[SvlteSST] " + s);
    }
}
