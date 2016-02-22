
package com.mediatek.internal.telephony.ltedc.svlte;

import android.os.Message;
import android.telephony.Rlog;
import android.telephony.ServiceState;

/**
 * MD IRAT data switch helper class.
 * @hide
 */
public class MdIratDataSwitchHelper extends IratDataSwitchHelper {

    private boolean mCdmaDataAllowed;
    private boolean mGsmDataAllowed;
    /**
     * Create MD IRAT data switch helper.
     * @param svltePhoneProxy Instance of SvltePhoneProxy.
     */
    public MdIratDataSwitchHelper(SvltePhoneProxy svltePhoneProxy) {
        super(svltePhoneProxy);
    }

    @Override
    protected void onCdmaDataAttached() {
        notifyDataConnectionAttached();
    }

    @Override
    protected void onLteDataAttached() {
        notifyDataConnectionAttached();
    }

    @Override
    protected void onCdmaDataDetached() {
        notifyDataConnectionDetached();
    }

    @Override
    protected void onLteDataDetached() {
        notifyDataConnectionDetached();
    }

    @Override
    protected void onCdmaDataAllowUrc() {
        mCdmaDataAllowed = true;
        notifyDataAllowed();
    }

    @Override
    protected void onGsmDataAllowUrc() {
        mGsmDataAllowed = true;
        notifyDataAllowed();
    }

    @Override
    protected void onCdmaSetDataAllowedDone() {
    }

    @Override
    protected void onGsmSetDataAllowedDone() {
    }

    @Override
    public void setDataAllowed(boolean allowed, Message result) {
        log("setDataAllowed: allowed = " + allowed);
        // always be handled by LTE modem.
        mLteCi.setDataAllowed(allowed, result);
    }

    @Override
    public void syncAndNotifyAttachState() {
        notifyDataConnectionAttached();
    }

    private void notifyDataConnectionAttached() {
        log("notifyDataConnectionAttached: mPsServiceType = " + mPsServiceType);
        if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
            if (getCurrentDataConnectionState(mCdmaPhone) == ServiceState.STATE_IN_SERVICE) {
                mAttachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
            if (getCurrentDataConnectionState(mLtePhone) == ServiceState.STATE_IN_SERVICE) {
                mAttachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_UNKNOWN) {
            if (getCurrentDataConnectionState(mLtePhone) == ServiceState.STATE_IN_SERVICE
                    && getCurrentDataConnectionState(mCdmaPhone) == ServiceState.STATE_IN_SERVICE) {
                mAttachedRegistrants.notifyRegistrants();
            }
        }
    }

    private void notifyDataConnectionDetached() {
        log("notifyDataConnectionDetached: mPsServiceType = " + mPsServiceType);
        if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
            if (getCurrentDataConnectionState(mCdmaPhone) != ServiceState.STATE_IN_SERVICE) {
                mDetachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
            if (getCurrentDataConnectionState(mLtePhone) != ServiceState.STATE_IN_SERVICE) {
                mDetachedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_UNKNOWN) {
            if (getCurrentDataConnectionState(mLtePhone) != ServiceState.STATE_IN_SERVICE
                    && getCurrentDataConnectionState(mCdmaPhone) == ServiceState.STATE_IN_SERVICE) {
                mDetachedRegistrants.notifyRegistrants();
            }
        }
    }

    private void notifyDataAllowed() {
        log("notifyDataAllowed: mCdmaDataAllowed = " + mCdmaDataAllowed
                + ", mGsmDataAllowed = " + mGsmDataAllowed
                + ", mPsServiceType = " + mPsServiceType);
        if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_CDMA) {
            if (mCdmaDataAllowed) {
                mDataAllowedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_ON_LTE) {
            if (mGsmDataAllowed) {
                mDataAllowedRegistrants.notifyRegistrants();
            }
        } else if (mPsServiceType == SvlteIratUtils.PS_SERVICE_UNKNOWN) {
            if (mGsmDataAllowed || mCdmaDataAllowed) {
                mDataAllowedRegistrants.notifyRegistrants();
            }
        }
    }

    protected void log(String s) {
        Rlog.d(LOG_TAG, "[MD_IRAT_DSH] " + s);
    }

    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[MD_IRAT_DSH] " + s);
    }
}
