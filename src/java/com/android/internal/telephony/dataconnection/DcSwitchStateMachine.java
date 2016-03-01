/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.dataconnection.DcSwitchAsyncChannel.RequestInfo;
import com.android.internal.telephony.SubscriptionController;

import android.os.Message;
import android.os.SystemProperties;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;

import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.ltedc.svlte.SvltePhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;

public class DcSwitchStateMachine extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String LOG_TAG = "DcSwitchSM";

    // ***** Event codes for driving the state machine
    private static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER + 0x00001000;
    private static final int EVENT_CONNECTED = BASE + 0;
    // MTK
    private static final int EVENT_DATA_DETACH_DONE = BASE + 1;

    private int mId;
    private Phone mPhone;
    private AsyncChannel mAc;

    private IdleState mIdleState = new IdleState();
    private AttachingState mAttachingState = new AttachingState();
    private AttachedState mAttachedState = new AttachedState();
    private DetachingState mDetachingState = new DetachingState();
    private PreDetachCheckState mPreDetachCheckState = new PreDetachCheckState();  // MTK
    private DefaultState mDefaultState = new DefaultState();

    // MTK
    private boolean mNeedAttach = true;
    private boolean mIsAttached = false;
    private String mReason = "";
    public static final String DCSTATE_IDLE = "idle";
    public static final String DCSTATE_ATTACHING = "attaching";
    public static final String DCSTATE_ATTACHED = "attached";
    public static final String DCSTATE_PREDETACH_CHECK = "predetachcheck";
    public static final String DCSTATE_DETACHING = "detaching";

    //[C2K] SvLte DSDS
    private static final int SIM_ID_NONE = -1;

    protected DcSwitchStateMachine(Phone phone, String name, int id) {
        super(name);
        if (DBG) log("DcSwitchState constructor E");
        mPhone = phone;
        mId = id;

        addState(mDefaultState);
        addState(mIdleState, mDefaultState);
        addState(mAttachingState, mDefaultState);
        addState(mAttachedState, mDefaultState);
        addState(mDetachingState, mDefaultState);
        addState(mPreDetachCheckState, mDefaultState);  // MTK
        setInitialState(mIdleState);
        if (DBG) log("DcSwitchState constructor X");
    }

//    public void notifyDataConnection(int phoneId, String state, String reason,
//            String apnName, String apnType, boolean unavailable) {
//        if (phoneId == mId &&
//                TextUtils.equals(state, PhoneConstants.DataState.CONNECTED.toString())) {
//            sendMessage(obtainMessage(EVENT_CONNECTED));
//        }
//    }

    private class IdleState extends State {
        @Override
        public void enter() {
            if (DBG) log("IdleState: enter");

            try {
                DctController.getInstance().processRequests();
                // MTK
                DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_IDLE, mId, mReason);
            } catch (RuntimeException e) {
                if (DBG) loge("DctController is not ready");
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    if (DBG) {
                        log("IdleState: REQ_CONNECT");
                    }

                    PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
                    pb.mCi.setDataAllowed(true, null);
                    boolean isPrimarySubFeatureEnable =
                            SystemProperties.getBoolean("persist.radio.primarycard", false);
                    int subId = pb.getSubId();
                    log("Setting default DDS on " + subId + " primary Sub feature"
                            + isPrimarySubFeatureEnable);

                    // When isPrimarySubFeatureEnable is enabled apps will take care
                    // of sending DDS request during device power-up.
                    if (!isPrimarySubFeatureEnable) {
                        SubscriptionController subscriptionController
                                = SubscriptionController.getInstance();
                        subscriptionController.setDefaultDataSubId(subId);
                    }

                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);

                    // MTK
                    if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                        log("REQ_CONNECT : mId = " + mId);
                        setPsActiveSim();
                    }

                    transitionTo(mAttachingState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_ATTACHED:
                    if (DBG) {
                        log("AttachingState: EVENT_DATA_ATTACHED");
                    }
                    transitionTo(mAttachedState);
                    retVal = HANDLED;
                    break;

                case EVENT_CONNECTED: {
                    if (DBG) {
                        log("IdleState: Receive invalid event EVENT_CONNECTED!");
                    }
                    retVal = HANDLED;
                    break;
                }
                // MTK
                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("IdleState: REQ_DISCONNECT_ALL, shouldn't in this state," +
                            "but regards it had disconnected");
                    }
                    mAc.replyToMessage(msg,
                            DcSwitchAsyncChannel.REQ_DISCONNECT_ALL,
                            PhoneConstants.APN_ALREADY_INACTIVE);
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("IdleState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class AttachingState extends State {
        // MTK
        boolean mTriggeredWithoutSIM = false;

        @Override
        public void enter() {
            // MTK
            log("AttachingState: enter mNeedAttach = " + mNeedAttach + " mTriggeredWithoutSIM: "
                + mTriggeredWithoutSIM);
            if (mNeedAttach) {
                // M: [C2K][IRAT] Change the format of RIL request
                setDataAllowed(true, null);
            } else {
                log("AttachingState: caused by lost connection, don't attach again");
                mNeedAttach = true;
                mReason = "Lost Connection";
            }
            DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_ATTACHING, mId, mReason);
            if (mTriggeredWithoutSIM) {
                log("send attached to transit to attached state for EIMS triggered without SIM");
                sendMessage(obtainMessage(DcSwitchAsyncChannel.EVENT_DATA_ATTACHED));
                mTriggeredWithoutSIM = false;
            }
            mReason = "";

            if (mPhone.getServiceState() != null &&
                    mPhone.getServiceState().getDataRegState() == ServiceState.STATE_IN_SERVICE) {
                log("AttachingState: Data already registered. Move to Attached");
                transitionTo(mAttachedState);
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    if (DBG) {
                        log("AttachingState: REQ_CONNECT");
                    }

                    // MTK
                    /// M: [C2K][IRAT] Set PS active slot..
                    if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                        log("REQ_CONNECT : mId = " + mId);
                        setPsActiveSim();
                    }

                    // M: [C2K][IRAT] Change the format of RIL request
                    setDataAllowed(true, null);

                    RequestInfo apnRequest = null;

                    if (msg.obj != null) {
                        apnRequest = (RequestInfo) msg.obj;
                        if (isRequestEIMSWithoutSIM(apnRequest)) {
                             sendMessage(obtainMessage(DcSwitchAsyncChannel.EVENT_DATA_ATTACHED));
                        }
                    }

                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);

                    // MTK
                    if (mIsAttached) {
                        if (DBG) {
                            log("AttachingState: already attached, transfer to AttachedState");
                        }
                        transitionTo(mAttachedState);
                    }

                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_ATTACHED:
                    if (DBG) {
                        log("AttachingState: EVENT_DATA_ATTACHED");
                    }
                    mIsAttached = true;  // MTK
                    transitionTo(mAttachedState);
                    retVal = HANDLED;
                    break;

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("AttachingState: REQ_DISCONNECT_ALL" );
                    }
                    DctController.getInstance().releaseAllRequests(mId);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT_ALL,
                            PhoneConstants.APN_REQUEST_STARTED);

                    transitionTo(mDetachingState);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("AttachingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class AttachedState extends State {
        @Override
        public void enter() {
            if (DBG) log("AttachedState: enter");
            //When enter attached state, we need exeute all requests.
            DctController.getInstance().executeAllRequests(mId);
            // MTK
            DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_ATTACHED, mId, mReason);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    RequestInfo apnRequest = (RequestInfo)msg.obj;
                    if (DBG) {
                        log("AttachedState: REQ_CONNECT, apnRequest=" + apnRequest);
                    }

                    DctController.getInstance().executeRequest(apnRequest);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);

                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT: {
                    RequestInfo apnRequest = (RequestInfo)msg.obj;
                    if (DBG) {
                        log("AttachedState: REQ_DISCONNECT apnRequest=" + apnRequest);
                    }

                    DctController.getInstance().releaseRequest(apnRequest);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);

                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("AttachedState: REQ_DISCONNECT_ALL" );
                    }
                    DctController.getInstance().releaseAllRequests(mId);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT_ALL,
                            PhoneConstants.APN_REQUEST_STARTED);

                    transitionTo(mDetachingState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_DETACHED: {
                    if (DBG) {
                        log("AttachedState: EVENT_DATA_DETACHED");
                    }
                    // MTK
                    mNeedAttach = false;
                    mIsAttached = false;

                    transitionTo(mAttachingState);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("AttachedState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    // MTK
    private class PreDetachCheckState extends State {
        int mUserCnt;
        int mTransactionId;

        @Override
        public void enter() {
            if (DBG) {
                log("PreDetachCheckState: enter");
            }
            DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_PREDETACH_CHECK, mId,
                mReason);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONFIRM_PREDETACH:
                    if (DBG) {
                        log("PreDetachCheckState: REQ_CONFIRM_PREDETACH");
                    }

                    DctController.getInstance().releaseAllRequests(mId);
                    transitionTo(mDetachingState);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONFIRM_PREDETACH,
                            PhoneConstants.APN_REQUEST_STARTED);
                    retVal = HANDLED;
                    break;
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    if (DBG) {
                        log("PreDetachCheckState: REQ_CONNECT, return fail");
                    }
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_FAILED);
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT: {
                    RequestInfo apnRequest = (RequestInfo) msg.obj;
                    if (DBG) {
                        log("PreDetachCheckState: REQ_DISCONNECT apnRequest=" + apnRequest);
                    }

                    DctController.getInstance().releaseRequest(apnRequest);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);

                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("PreDetachCheckState: REQ_DISCONNECT_ALL, return fail.");
                    }
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT_ALL,
                            PhoneConstants.APN_REQUEST_FAILED);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("PreDetachCheckState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DetachingState extends State {
        @Override
        public void enter() {
            if (DBG) log("DetachingState: enter");
            PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
            pb.mCi.setDataAllowed(false, obtainMessage(
                    DcSwitchAsyncChannel.EVENT_DATA_DETACHED));
            // MTK
            // M: [C2K][IRAT] Change the format of RIL request
            setDataAllowed(false, obtainMessage(EVENT_DATA_DETACH_DONE));
            DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_DETACHING, mId, mReason);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.EVENT_DATA_DETACHED: {
                    if (DBG) {
                        log("DetachingState: EVENT_DATA_DETACHED");
                    }
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DetachingState: REQ_DISCONNECT_ALL, already detaching" );
                    }
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT_ALL,
                            PhoneConstants.APN_REQUEST_STARTED);
                    retVal = HANDLED;
                    break;
                }

                // MTK
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    if (DBG) {
                        log("DetachingState: REQ_CONNECT, return fail");
                    }
                    // M: [C2K][IRAT] Change the format of RIL request
                    setDataAllowed(false, obtainMessage(EVENT_DATA_DETACH_DONE));
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_FAILED);
                    retVal = HANDLED;
                    break;
                }

                case EVENT_DATA_DETACH_DONE: {
                    if (DBG) {
                        log("DeattachingState: EVENT_DATA_DETACH_DONE");
                    }
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("DetachingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                    mAc.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECTED");
                    mAc = null;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_STATE: {
                    boolean val = getCurrentState() == mIdleState;
                    if (VDBG) log("REQ_IS_IDLE_STATE  isIdle=" + val);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_IS_IDLE_STATE, val ? 1 : 0);
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_OR_DETACHING_STATE: {
                    boolean val = (getCurrentState() == mIdleState ||
                            getCurrentState() == mDetachingState);
                    if (VDBG) log("REQ_IS_IDLE_OR_DETACHING_STATE  isIdleDetaching=" + val);
                    mAc.replyToMessage(msg,
                            DcSwitchAsyncChannel.RSP_IS_IDLE_OR_DETACHING_STATE, val ? 1 : 0);
                    break;
                }
                // MTK
                case DcSwitchAsyncChannel.REQ_CONFIRM_PREDETACH:
                    if (DBG) {
                        log("DefaultState: unhandle REQ_PRECHECK_DONE");
                    }
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONFIRM_PREDETACH,
                            PhoneConstants.APN_REQUEST_FAILED);
                    break;
                case DcSwitchAsyncChannel.EVENT_DATA_DETACHED:
                    if (DBG) {
                        log("DefaultState: EVENT_DATA_DETACHED");
                    }
                    mIsAttached = false;
                    break;
                default:
                    if (DBG) {
                        log("DefaultState: shouldn't happen but ignore msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    break;
            }
            return HANDLED;
        }
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[" + getName() + "] " + s);
    }

    // MTK

    /// M: [C2K][IRAT] code start {@
    private void setPsActiveSim() {
        //Should setPsActiveSim in SVLTE project, not just only for ACTIVE SVLTE slot
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            ((SvltePhoneProxy) mPhone).getRilDcArbitrator().requestSetPsActiveSlot(
                    mId + 1, null);
        }
    }

    private void setDataAllowed(boolean allowed, Message result) {
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() && SvlteUtils.isActiveSvlteMode(mId)) {
            ((SvltePhoneProxy) mPhone).getIratDataSwitchHelper()
                    .setDataAllowed(allowed, result);
        } else {
            PhoneBase phone = (PhoneBase) ((PhoneProxy) mPhone)
                    .getActivePhone();
            phone.mCi.setDataAllowed(allowed, result);
        }
    }
    /// M: [C2K][IRAT] code end @}

    /// M: VOLTE code start @}
    private boolean isRequestEIMSWithoutSIM(RequestInfo apnRequest) {
        boolean bRet = false;
        boolean bhasEIMSCap = apnRequest.request.networkCapabilities.hasCapability(
                                                NetworkCapabilities.NET_CAPABILITY_EIMS);
        String specifier = apnRequest.request.networkCapabilities.getNetworkSpecifier();
        log("bHasEIMSCap: " + bhasEIMSCap + ", specifier: " + specifier);

        if (bhasEIMSCap && (specifier != null && !specifier.equals("") )) {
            int subId = Integer.parseInt(specifier);
            if (subId < SubscriptionManager.INVALID_PHONE_INDEX) {
                bRet = true;
            }
        }
        log("isRequestEIMSWithoutSIM ret: " + bRet);
        return bRet;
    }
    /// M: VOLTE code end @}
}
