package com.mediatek.internal.telephony.dataconnection;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;

import android.provider.Settings;

import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.ITelephonyEx;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;
import com.mediatek.internal.telephony.RadioCapabilitySwitchUtil;

import java.util.Arrays;

public class DataSubSelector {
    private static final boolean DBG = true;

    private int mPhoneNum;
    private boolean mIsNeedWaitImsi = false;
    private boolean mIsNeedWaitUnlock = false;
    private static final String PROPERTY_DEFAULT_DATA_ICCID = "persist.radio.data.iccid";
    private static final String NO_SIM_VALUE = "N/A";

    private static final boolean BSP_PACKAGE = true;
    //         SystemProperties.getBoolean("ro.mtk_bsp_package", false);

    private static String mOperatorSpec;
    private static final String OPERATOR_OM = "OM";
    private static final String OPERATOR_OP01 = "OP01";
    private static final String OPERATOR_OP02 = "OP02";
    private static final String OPERATOR_OP09 = "OP09";
    

    private static final String PROPERTY_3G_SIM = "persist.radio.simswitch";

    public static final String ACTION_MOBILE_DATA_ENABLE
            = "android.intent.action.ACTION_MOBILE_DATA_ENABLE";
    public static final String EXTRA_MOBILE_DATA_ENABLE_REASON = "reason";

    public static final String REASON_MOBILE_DATA_ENABLE_USER = "user";
    public static final String REASON_MOBILE_DATA_ENABLE_SYSTEM = "system";

    private static final String PROPERTY_MOBILE_DATA_ENABLE = "persist.radio.mobile.data";
    private String[] PROPERTY_ICCID = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };


    protected BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("onReceive: action=" + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                mIsNeedWaitImsi = false;
                onSubInfoReady(intent);
            } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                int slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY, PhoneConstants.SIM_ID_1);
                log("slotId: " + slotId + " simStatus: " + simStatus + " mIsNeedWaitImsi: "
                        + mIsNeedWaitImsi + " mIsNeedWaitUnlock: " + mIsNeedWaitUnlock);
                if (simStatus.equals(IccCardConstants.INTENT_VALUE_ICC_IMSI)) {
                    if (mIsNeedWaitImsi == true) {
                        log("get imsi and need to check op01 again");
                        mIsNeedWaitImsi = false;
                        if (checkOp01CapSwitch() == false) {
                            mIsNeedWaitImsi = true;
                        }
                    } else if (mIsNeedWaitUnlock == true) {
                        log("get imsi because unlock");
                        
                        ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                            ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
                        try {
                            if (iTelEx.isCapabilitySwitching()) {
                                // wait complete intent
                            } else {
                                mIsNeedWaitUnlock = false;
                                checkOp01CapSwitch();
                            }
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE)
                    || action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_FAILED)) {
                if (mIsNeedWaitUnlock == true) {
                    mIsNeedWaitUnlock = false;
                    checkOp01CapSwitch();
                }
            } else if (action.equals(TelephonyIntents.ACTION_SET_RADIO_TECHNOLOGY_DONE)) {
                log("ACTION_SET_RADIO_TECHNOLOGY_DONE");
                if (!mOperatorSpec.equals(OPERATOR_OP09)
                        || !mOperatorSpec.equals(OPERATOR_OP02)
                        || !mOperatorSpec.equals(OPERATOR_OP01)) {
                    subSelectorForSvlte(intent);
                }
            }
        }
    };

    public DataSubSelector(Context context, int phoneNum) {
        log("DataSubSelector is created");
        mPhoneNum = phoneNum;
        mOperatorSpec = SystemProperties.get("ro.operator.optr", OPERATOR_OM);
        log("Operator Spec:" + mOperatorSpec);

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_FAILED);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_TECHNOLOGY_DONE);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    private void onSubInfoReady(Intent intent) {

        if (BSP_PACKAGE) {
            log("Don't support BSP Package.");
            return;
        }
        /*
        if (mOperatorSpec.equals(OPERATOR_OP01)) {
            subSelectorForOp01(intent);
        } else if (mOperatorSpec.equals(OPERATOR_OP02)) {
            subSelectorForOp02(intent);
        } else if (mOperatorSpec.equals(OPERATOR_OP09)) {
            subSelectorForOp09(intent);
        } else if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            // wait for ACTION_RADIO_TECHNOLOGY_CHANGED
            //Turn off data if new SIM detected.
            turnOffNewSimData(intent);
        } else {
            subSelectorForOm(intent);
        }

        updateDataEnableProperty();
        */
    }

    private void subSelectorForOm(Intent intent) {
        log("DataSubSelector for OM: only for capability switch; for default data, use google");

        // only handle 3/4G switching
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        String[] currIccId = new String[mPhoneNum];

        //Since SvLTE Project may call subSelectorForOm before sub ready
        //we should do this on sub ready
        if (!CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            turnOffNewSimData(intent);
        }

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);
        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (defaultIccid.equals(currIccId[i])) {
                phoneId = i;
                break;
            }
        }
        log("Default data phoneid = " + phoneId);
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            // always set capability to this phone
            setCapability(phoneId);
        }
    }

    private boolean checkOp01CapSwitch() {
        // check if need to switch capability
        // op01 USIM > op01 SIM > oversea USIM > oversea SIM > others
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int targetSim = -1;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        boolean[] op01Usim = new boolean[mPhoneNum];
        boolean[] op01Sim = new boolean[mPhoneNum];
        boolean[] overseaUsim = new boolean[mPhoneNum];
        boolean[] overseaSim = new boolean[mPhoneNum];
        String capabilitySimIccid = SystemProperties.get(RadioCapabilitySwitchUtil.MAIN_SIM_PROP);
        String[] currIccId = new String[mPhoneNum];

        log("checkOp01CapSwitch start");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("checkOp01CapSwitch : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }
        // check pin lock
        String propStr;
        for (int i = 0; i < mPhoneNum; i++) {
            if (i == 0) {
                propStr = "gsm.sim.ril.mcc.mnc";
            } else {
                propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
            }
            if (SystemProperties.get(propStr, "").equals("sim_lock")) {
                log("checkOp01CapSwitch : phone " + i + " is sim lock");
                mIsNeedWaitUnlock = true;
            }
        }
        int capabilitySimId = Integer.valueOf(
                SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1")) - 1;
        log("op01: capabilitySimIccid:" + capabilitySimIccid
                + "capabilitySimId:" + capabilitySimId);
        for (int i = 0; i < mPhoneNum; i++) {
            // update SIM status
            if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP01) {
                if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    op01Usim[i] = true;
                } else {
                    op01Sim[i] = true;
                }
            } else if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OVERSEA) {
                if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    overseaUsim[i] = true;
                } else {
                    overseaSim[i] = true;
                }
            }
        }
        // dump sim op info
        log("op01Usim: " + Arrays.toString(op01Usim));
        log("op01Sim: " + Arrays.toString(op01Sim));
        log("overseaUsim: " + Arrays.toString(overseaUsim));
        log("overseaSim: " + Arrays.toString(overseaSim));

        for (int i = 0; i < mPhoneNum; i++) {
            if (capabilitySimIccid.equals(currIccId[i])) {
                targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(i, op01Usim
                        , op01Sim, overseaUsim, overseaSim);
                log("op01: i = " + i + ", currIccId : " + currIccId[i] + ", targetSim : " + targetSim);
                // default capability SIM is inserted
                if (op01Usim[i] == true) {
                    log("op01-C1: cur is old op01 USIM, no change");
                    if (capabilitySimId != i) {
                        log("op01-C1a: old op01 USIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (op01Sim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C2: cur is old op01 SIM but find op01 USIM, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C2a: old op01 SIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (overseaUsim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C3: cur is old OS USIM but find op01 SIMs, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C3a: old OS USIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (overseaSim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C4: cur is old OS SIM but find op01 SIMs/OS USIM, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C4a: old OS SIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (targetSim != -1) {
                    log("op01-C5: cur is old non-op01 SIM/USIM but find higher SIM, change!");
                    setCapability(targetSim);
                    return true;
                }
                log("op01-C6: no higher priority SIM, no cahnge");
                return true;
            }
        }
        // cannot find default capability SIM, check if higher priority SIM exists
        targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(capabilitySimId,
                op01Usim, op01Sim, overseaUsim, overseaSim);
        log("op01: target SIM :" + targetSim);
        if (op01Usim[capabilitySimId] == true) {
            log("op01-C7: cur is new op01 USIM, no change");
            return true;
        } else if (op01Sim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C8: cur is new op01 SIM but find op01 USIM, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (overseaUsim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C9: cur is new OS USIM but find op01 SIMs, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (overseaSim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C10: cur is new OS SIM but find op01 SIMs/OS USIM, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (targetSim != -1) {
            log("op01-C11: cur is non-op01 but find higher priority SIM, change!");
            setCapability(targetSim);
        } else {
            log("op01-C12: no higher priority SIM, no cahnge");
        }
        return true;
    }

    private void setDataEnabled(int phoneId, boolean enable) {
        log("setDataEnabled: phoneId=" + phoneId + ", enable=" + enable);

        TelephonyManager telephony = TelephonyManager.getDefault();
        if (telephony != null) {
            if (phoneId == SubscriptionManager.INVALID_PHONE_INDEX) {
                telephony.setDataEnabled(enable);
            } else {
                int phoneSubId = 0;
                if (enable == false) {
                    phoneSubId = PhoneFactory.getPhone(phoneId).getSubId();
                    log("Set Sub" + phoneSubId + " to disable");
                    telephony.setDataEnabled(phoneSubId, enable);
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        phoneSubId = PhoneFactory.getPhone(i).getSubId();
                        if (i != phoneId) {
                            log("Set Sub" + phoneSubId + " to disable");
                            telephony.setDataEnabled(phoneSubId, false);
                        } else {
                            log("Set Sub" + phoneSubId + " to enable");
                            telephony.setDataEnabled(phoneSubId, true);
                        }
                    }
                }
            }
        }
    }

    private void subSelectorForSvlte(Intent intent) {
        int c2kP2 = Integer.valueOf(
                SystemProperties.get("ro.mtk.c2k.slot2.support", "0"));
        log("subSelectorForSvlte, mode : " + SvlteModeController.getRadioTechnologyMode() +
                 "c2kP2 = " + c2kP2);
        if (c2kP2 == 0) {
           if (RadioCapabilitySwitchUtil.isSimContainsCdmaApp(PhoneConstants.SIM_ID_1)) {
              log("CDMA sim is inserted in slot1, always set to SIM1");
              setCapability(PhoneConstants.SIM_ID_1);
              return;
           }
           if (SvlteModeController.getRadioTechnologyMode() ==
                   SvlteModeController.RADIO_TECH_MODE_SVLTE) {
              // svlte mode
              // check sim 1 status
              int[] cardType = new int[TelephonyManager.getDefault().getPhoneCount()];
              cardType = UiccController.getInstance().getC2KWPCardType();
              log("card type: " + cardType[PhoneConstants.SIM_ID_1]);
              if (cardType[PhoneConstants.SIM_ID_1] == UiccController.CARD_TYPE_NONE) {
                  log("SIM 1 is empty, don't change capability");
              } else {
                  log("SIM 1 is inserted, change capability");
                  setCapability(PhoneConstants.SIM_ID_1);
              }
           } else {
              // csfb mode, follow om project switch
              subSelectorForOm(intent);
           }

        } else if (c2kP2 == 1) {
           // solution2 option2, follow om project switch
           subSelectorForOm(intent);
        }

    }

    private void setDefaultData(int phoneId) {
        SubscriptionController subController = SubscriptionController.getInstance();
        int sub = SubscriptionManager.getSubIdUsingPhoneId(phoneId);
        long currSub = SubscriptionManager.getDefaultDataSubId();

        log("setDefaultData: " + sub + ", current default sub:" + currSub);
        if (sub != currSub) {
            subController.setDefaultDataSubIdWithoutCapabilitySwitch(sub);
        } else {
            log("setDefaultData: default data unchanged");
        }
    }

    private void turnOffNewSimData(Intent intent) {
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        log("turnOffNewSimData detectedType = " + detectedType);

        //L MR1 Spec, turn off data if new sim inserted.
        if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
            int newSimSlot = intent.getIntExtra(
                    SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

            log("newSimSlot = " + newSimSlot);

            for (int i = 0; i < mPhoneNum; i++) {
                if ((newSimSlot & (1 << i)) != 0) {
                    String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
                    String newSimIccid = SystemProperties.get(PROPERTY_ICCID[i]);
                    if (!newSimIccid.equals(defaultIccid)) {
                        log("Detect NEW SIM, turn off phone " + i + " data.");
                        setDataEnabled(i, false);
                    }
                }
            }
        }
    }

    private boolean setCapability(int phoneId) {
        int[] phoneRat = new int[mPhoneNum];
        boolean isSwitchSuccess = true;

        log("setCapability: " + phoneId);

        String curr3GSim = SystemProperties.get(PROPERTY_3G_SIM, "");
        log("current 3G Sim = " + curr3GSim);

        if (curr3GSim != null && !curr3GSim.equals("")) {
            int curr3GPhoneId = Integer.parseInt(curr3GSim);
            if (curr3GPhoneId == (phoneId + 1) ) {
                log("Current 3G phone equals target phone, don't trigger switch");
                return isSwitchSuccess;
            }
        }

        try {
            ITelephony iTel = ITelephony.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE));
            ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
            if (null == iTel) {
                loge("Can not get phone service");
                return false;
            }

            int currRat = iTel.getRadioAccessFamily(phoneId);
            log("Current phoneRat:" + currRat);

            RadioAccessFamily[] rat = new RadioAccessFamily[mPhoneNum];
            for (int i = 0; i < mPhoneNum; i++) {
                if (phoneId == i) {
                    log("SIM switch to Phone" + i);
                    phoneRat[i] = RadioAccessFamily.RAF_LTE
                            | RadioAccessFamily.RAF_UMTS
                            | RadioAccessFamily.RAF_GSM;
                } else {
                    phoneRat[i] = RadioAccessFamily.RAF_GSM;
                }
                rat[i] = new RadioAccessFamily(i, phoneRat[i]);
            }
            if (false  == iTelEx.setRadioCapability(rat)) {
                log("Set phone rat fail!!!");
                isSwitchSuccess = false;
            }
        } catch (RemoteException ex) {
            log("Set phone rat fail!!!");
            ex.printStackTrace();
            isSwitchSuccess = false;
        }

        return isSwitchSuccess;
    }

    private void log(String txt) {
        if (DBG) {
            Rlog.d("DataSubSelector", txt);
        }
    }

    private void loge(String txt) {
        if (DBG) {
            Rlog.e("DataSubSelector", txt);
        }
    }
}
