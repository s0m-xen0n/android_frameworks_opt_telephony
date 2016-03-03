/*
 * Copyright (C) 2014 The OmniROM Project <http://www.omnirom.org>
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

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.Display;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Vector;

import java.io.IOException;
import java.io.InputStream;

import com.android.internal.telephony.RadioCapability;
import com.android.internal.telephony.cdma.CdmaSmsBroadcastConfigInfo;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.gsm.SsData;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.SpnOverride;
import com.android.internal.telephony.uicc.UiccController;

// import com.android.internal.telephony.uicc.SpnOverride;
import com.mediatek.internal.telephony.FemtoCellInfo;
import com.mediatek.internal.telephony.NetworkInfoWithAcT;
import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.ltedc.svlte.MdIratInfo;
import com.mediatek.internal.telephony.ltedc.svlte.MdIratInfo.IratType;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;
import com.mediatek.internal.telephony.uicc.SvlteUiccUtils;

//VoLTE
import com.mediatek.internal.telephony.QosStatus;
import com.mediatek.internal.telephony.TftStatus;
import com.mediatek.internal.telephony.DedicateDataCallState;
import com.mediatek.internal.telephony.PacketFilterInfo;
import com.mediatek.internal.telephony.TftAuthToken;
import com.mediatek.internal.telephony.PcscfInfo;
import com.mediatek.internal.telephony.DefaultBearerConfig;
import com.mediatek.internal.telephony.SrvccCallContext;

public class MediaTekRIL extends RIL implements CommandsInterface {

    // TODO: Support multiSIM
    // Sim IDs are 0 / 1
    int mSimId = 0;

    /* ALPS00799783: for restore previous preferred network type when set type fail */
    private int mPreviousPreferredType = -1;

    // MTK
    private static final String  PROPERTY_RIL_CARD_TYPE_SET = "gsm.ril.cardtypeset";
    private static final String  PROPERTY_RIL_CARD_TYPE_SET_2 = "gsm.ril.cardtypeset.2";
    private static final String  PROPERTY_NET_CDMA_MDMSTAT = "net.cdma.mdmstat";
    private static final int INITIAL_RETRY_INTERVAL_MSEC = 200;
    private static final String  PROPERTY_CONFIG_EMDSTATUS_SEND = "ril.cdma.emdstatus.send";
    /// M: C2K RILD socket name definition
    static final String C2K_SOCKET_NAME_RIL = "rild-via";
    private static final String[]  PROPERTY_RIL_FULL_UICC_TYPE = {
        "gsm.ril.fulluicctype",
        "gsm.ril.fulluicctype.2",
        "gsm.ril.fulluicctype.3",
        "gsm.ril.fulluicctype.4",
    };
    private static final int CARD_TYPE_SIM  = 1;
    private static final int CARD_TYPE_USIM = 2;
    private static final int CARD_TYPE_CSIM = 4;
    private static final int CARD_TYPE_RUIM = 8;

    private SpnOverride mSpnOverride;

    // for modem config
    private final Handler mHandler;

    /**
     * Receives and stores the capabilities supported by the modem.
     */
    private Handler mSupportedRafHandler;

    /* M: call control part start */
    /* DTMF request will be ignored when duplicated sending */
    private class dtmfQueueHandler {

        public dtmfQueueHandler() {
            mDtmfStatus = DTMF_STATUS_STOP;
        }

        public void start() {
            mDtmfStatus = DTMF_STATUS_START;
        }

        public void stop() {
            mDtmfStatus = DTMF_STATUS_STOP;
        }

        public boolean isStart() {
            return (mDtmfStatus == DTMF_STATUS_START);
        }

        public void add(RILRequest o) {
            mDtmfQueue.addElement(o);
        }

        public void remove(RILRequest o) {
            mDtmfQueue.remove(o);
        }

        public void remove(int idx) {
            mDtmfQueue.removeElementAt(idx);
        }

        public RILRequest get() {
            return (RILRequest) mDtmfQueue.get(0);
        }

        public int size() {
            return mDtmfQueue.size();
        }

        public void setPendingRequest(RILRequest r) {
            mPendingCHLDRequest = r;
        }

        public RILRequest getPendingRequest() {
            return mPendingCHLDRequest;
        }

        public void setSendChldRequest() {
            mIsSendChldRequest = true;
        }

        public void resetSendChldRequest() {
            mIsSendChldRequest = false;
        }

        public boolean hasSendChldRequest() {
            riljLog("mIsSendChldRequest = " + mIsSendChldRequest);
            return mIsSendChldRequest;
        }

        public final int MAXIMUM_DTMF_REQUEST = 32;
        private final boolean DTMF_STATUS_START = true;
        private final boolean DTMF_STATUS_STOP = false;

        private boolean mDtmfStatus = DTMF_STATUS_STOP;
        private Vector mDtmfQueue = new Vector(MAXIMUM_DTMF_REQUEST);

        private RILRequest mPendingCHLDRequest = null;
        private boolean mIsSendChldRequest = false;
    }

    private dtmfQueueHandler mDtmfReqQueue = new dtmfQueueHandler();
    /* M: call control part end */

    public MediaTekRIL(Context context, int networkMode, int cdmaSubscription) {
        this(context, networkMode, cdmaSubscription, null);
    }

    public MediaTekRIL(Context context, int networkMode, int cdmaSubscription, Integer instanceId) {
	    super(context, networkMode, cdmaSubscription, instanceId);

	    // xen0n: don't know why this can NPE, init in ctor instead. sigh
	    riljLog("MediaTekRIL ctor!");
	    mSupportedRafHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                AsyncResult ar = (AsyncResult) msg.obj;
                RadioCapability rc = (RadioCapability) ar.result;
                if (ar.exception != null) {
                    if (RILJ_LOGD) Rlog.e(RILJ_LOG_TAG, "Get supported radio access family fail" + (mInstanceId != null ? (" [SUB" + mInstanceId + "]") : ""), ar.exception);
                } else {
                    mSupportedRaf = rc.getRadioAccessFamily();
                    if (RILJ_LOGD) riljLog("Supported radio access family=" + mSupportedRaf);
                }
            }
        };

        // and its friends
        mSpnOverride = new SpnOverride();
        mHandler = new Handler();
    }

    public static byte[] hexStringToBytes(String s) {
        byte[] ret;

        if (s == null) return null;

        int len = s.length();
        ret = new byte[len/2];

        for (int i=0 ; i <len ; i+=2) {
            ret[i/2] = (byte) ((hexCharToInt(s.charAt(i)) << 4)
                                | hexCharToInt(s.charAt(i+1)));
        }

        return ret;
    }

    static int hexCharToInt(char c) {
         if (c >= '0' && c <= '9') return (c - '0');
         if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
         if (c >= 'a' && c <= 'f') return (c - 'a' + 10);

         throw new RuntimeException ("invalid hex char '" + c + "'");
    }

    private Object
    responseStringEncodeBase64(Parcel p) {
        String response;

        response = p.readString();

        if (RILJ_LOGD) {
            riljLog("responseStringEncodeBase64 - Response = " + response);
        }

        byte[] auth_output = new byte[response.length() / 2];
		for (int i = 0; i < auth_output.length; i++) {
		    auth_output[i] |= Character.digit(response.charAt(i * 2), 16) * 16;
		    auth_output[i] |= Character.digit(response.charAt(i * 2 + 1), 16);
		}
		response = android.util.Base64.encodeToString(auth_output, android.util.Base64.NO_WRAP);

        if (RILJ_LOGD) {
            riljLog("responseStringEncodeBase64 - Encoded Response = " + response);
        }

        return response;
    }

    protected Object
    responseOperatorInfos(Parcel p) {
        if (mInstanceId == null || mInstanceId == 0) {
            mSimId = 0;
        } else {
            mSimId = mInstanceId;
        }

        String strings[] = (String [])responseStrings(p);
        ArrayList<OperatorInfo> ret;

        if (strings.length % 4 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                + strings.length + " strings, expected multiples of 4");
        }

        String lacStr = SystemProperties.get("gsm.cops.lac");
        boolean lacValid = false;
        int lacIndex=0;

        Rlog.d(RILJ_LOG_TAG, "lacStr = " + lacStr+" lacStr.length="+lacStr.length()+" strings.length="+strings.length);
        if((lacStr.length() > 0) && (lacStr.length()%4 == 0) && ((lacStr.length()/4) == (strings.length/5 ))){
            Rlog.d(RILJ_LOG_TAG, "lacValid set to true");
            lacValid = true;
        }

        SystemProperties.set("gsm.cops.lac","");

        ret = new ArrayList<OperatorInfo>(strings.length / 4);

        for (int i = 0 ; i < strings.length ; i += 4) {
            if((strings[i+0] != null) && (strings[i+0].startsWith("uCs2") == true)) {
                riljLog("responseOperatorInfos handling UCS2 format name");

                try {
                    strings[i+0] = new String(hexStringToBytes(strings[i+0].substring(4)), "UTF-16");
                } catch(UnsupportedEncodingException ex) {
                    riljLog("responseOperatorInfos UnsupportedEncodingException");
                }
            }

            if ((lacValid == true) && (strings[i] != null)) {
                UiccController uiccController = UiccController.getInstance();
                IccRecords iccRecords = uiccController.getIccRecords(mSimId, UiccController.APP_FAM_3GPP);
                int lacValue = -1;
                String sEons = null;
                String lac = lacStr.substring(lacIndex,lacIndex+4);
                Rlog.d(RILJ_LOG_TAG, "lacIndex="+lacIndex+" lacValue="+lacValue+" lac="+lac+" plmn numeric="+strings[i+2]+" plmn name"+strings[i+0]);

                if(lac != "") {
                    lacValue = Integer.parseInt(lac, 16);
                    lacIndex += 4;
                    if(lacValue != 0xfffe) {
                        /*sEons = iccRecords.getEonsIfExist(strings[i+2],lacValue,true);
                        if(sEons != null) {
                            strings[i] = sEons;
                            Rlog.d(RILJ_LOG_TAG, "plmn name update to Eons: "+strings[i]);
                        }*/
                    } else {
                        Rlog.d(RILJ_LOG_TAG, "invalid lac ignored");
                    }
                }
            }

            if (strings[i] != null && (strings[i].equals("") || strings[i].equals(strings[i+2]))) {
                Operators init = new Operators ();
                String temp = init.unOptimizedOperatorReplace(strings[i+2]);
                riljLog("lookup RIL responseOperatorInfos() " + strings[i+2] + " gave " + temp);
                strings[i] = temp;
                strings[i+1] = temp;
            }

            // NOTE: We don't have the 5th element in MTK, and I don't know about
            // the cases that make this processing necessary. Disable for now.
            /*
            // 1, 2 = 2G
            // > 2 = 3G
            String property_name = "gsm.baseband.capability";
            if(mSimId > 0) {
                property_name = property_name + (mSimId+1);
            }

            int basebandCapability = SystemProperties.getInt(property_name, 3);
            Rlog.d(RILJ_LOG_TAG, "property_name="+property_name+", basebandCapability=" + basebandCapability);
            if (3 < basebandCapability) {
                strings[i+0] = strings[i+0].concat(" " + strings[i+4]);
                strings[i+1] = strings[i+1].concat(" " + strings[i+4]);
            }
            */

            ret.add(
                new OperatorInfo(
                    strings[i+0],
                    strings[i+1],
                    strings[i+2],
                    strings[i+3]));
        }

        return ret;
    }

    private Object
    responseCrssNotification(Parcel p) {
        /*SuppCrssNotification notification = new SuppCrssNotification();

        notification.code = p.readInt();
        notification.type = p.readInt();
        notification.number = p.readString();
        notification.alphaid = p.readString();
        notification.cli_validity = p.readInt();

        return notification;*/

        Rlog.e(RILJ_LOG_TAG, "NOT PROCESSING CRSS NOTIFICATION");
        return null;
    }

    private Object responseEtwsNotification(Parcel p) {
        /*EtwsNotification response = new EtwsNotification();

        response.warningType = p.readInt();
        response.messageId = p.readInt();
        response.serialNumber = p.readInt();
        response.plmnId = p.readString();
        response.securityInfo = p.readString();

        return response;*/
        Rlog.e(RILJ_LOG_TAG, "NOT PROCESSING ETWS NOTIFICATION");

        return null;
    }

    private Object responseSetupDedicateDataCall(Parcel p) {
        int number = p.readInt();
        if (RILJ_LOGD) riljLog("responseSetupDedicateDataCall number=" + number);
        DedicateDataCallState[] dedicateDataCalls = new DedicateDataCallState[number];
        for (int i=0; i<number; i++) {
            DedicateDataCallState dedicateDataCall = new DedicateDataCallState();
            dedicateDataCall.readFrom(p);
            dedicateDataCalls[i] = dedicateDataCall;

            riljLog("[" + dedicateDataCall.interfaceId + ", " + dedicateDataCall.defaultCid + ", " + dedicateDataCall.cid + ", " + dedicateDataCall.active +
                ", " + dedicateDataCall.signalingFlag + ", " + dedicateDataCall.failCause + ", Qos" + dedicateDataCall.qosStatus +
                ", Tft" + dedicateDataCall.tftStatus + ", PCSCF" + dedicateDataCall.pcscfInfo);
        }

        if (number > 1)
            return dedicateDataCalls;
        else if (number > 0)
            return dedicateDataCalls[0];
        else
            return null;
    }

    private Object
    responseOperatorInfosWithAct(Parcel p) {
        Rlog.e(RILJ_LOG_TAG, "responseOperatorInfosWithAct: stub!");

        return null;
    }

    private Object
    responseFemtoCellInfos(Parcel p) {
        Rlog.e(RILJ_LOG_TAG, "responseFemtoCellInfos: stub!");

        return null;
    }

    private Object
    responseSmsParams(Parcel p) {
        Rlog.e(RILJ_LOG_TAG, "responseSmsParams: stub!");

        return null;
    }

    private Object
    responseSimSmsMemoryStatus(Parcel p) {
        Rlog.e(RILJ_LOG_TAG, "responseSimSmsMemoryStatus: stub!");

        return null;
    }

    private Object
    responseCbConfig(Parcel p) {
        Rlog.e(RILJ_LOG_TAG, "responseCbConfig: stub!");

        return null;
    }

    private Object
    responseModifyDataCall(Parcel p) {
        return null;
    }

    private Object
    responsePcscfDiscovery(Parcel p) {
        PcscfInfo pcscfInfo = null;
        String pcscfStr = p.readString();
        if (!TextUtils.isEmpty(pcscfStr)) {
            String[] pcscfArray = pcscfStr.split(" ");
            if (pcscfArray != null && pcscfArray.length > 0)
                pcscfInfo = new PcscfInfo(PcscfInfo.IMC_PCSCF_ACQUIRE_BY_PCO, pcscfArray);
        }
        return pcscfInfo;
    }

    private Object
    responseGetNitzTime(Parcel p) {
        Object[] result = new Object[2];
        String response;

        response = p.readString();
        long nitzReceiveTime = p.readLong();
        result[0] = response;
        result[1] = Long.valueOf(nitzReceiveTime);

        return result;
    }
    
    private Object
    responsePhbEntries(Parcel p) {
        Rlog.e(RILJ_LOG_TAG, "responsePhbEntries: stub!");

        return null;
    }

    private Object
    responseGetPhbMemStorage(Parcel p) {
        Rlog.e(RILJ_LOG_TAG, "responseGetPhbMemStorage: stub!");

        return null;
    }

    private Object responseReadPhbEntryExt(Parcel p) {
        /*
        int numerOfEntries;
        PBEntry[] response;

        numerOfEntries = p.readInt();
        response = new PBEntry[numerOfEntries];

        Rlog.d(RILJ_LOG_TAG, "responseReadPhbEntryExt Number: " + numerOfEntries);

        for (int i = 0; i < numerOfEntries; i++) {
            response[i] = new PBEntry();
            response[i].setIndex1(p.readInt());
            response[i].setNumber(p.readString());
            response[i].setType(p.readInt());
            response[i].setText(p.readString());
            response[i].setHidden(p.readInt());

            response[i].setGroup(p.readString());
            response[i].setAdnumber(p.readString());
            response[i].setAdtype(p.readInt());
            response[i].setSecondtext(p.readString());
            response[i].setEmail(p.readString());
            Rlog.d(RILJ_LOG_TAG, "responseReadPhbEntryExt[" + i + "] " + response[i].toString());
        }

        return response;
        */
        Rlog.e(RILJ_LOG_TAG, "responseReadPhbEntryExt: stub!");

        return null;
    }

    private Object
    responseRadioCapability(Parcel p) {
        int version = p.readInt();
        int session = p.readInt();
        int phase = p.readInt();
        int rat = p.readInt();
        String logicModemUuid = p.readString();
        int status = p.readInt();

        riljLog("responseRadioCapability: version= " + version +
                ", session=" + session +
                ", phase=" + phase +
                ", rat=" + rat +
                ", logicModemUuid=" + logicModemUuid +
                ", status=" + status);
        RadioCapability rc = new RadioCapability(
                mInstanceId.intValue(), session, phase, rat, logicModemUuid, status);
        return rc;
    }

    private Object
    responseSsData(Parcel p) {
        int num;
        SsData ssData = new SsData();

        ssData.serviceType = ssData.ServiceTypeFromRILInt(p.readInt());
        ssData.requestType = ssData.RequestTypeFromRILInt(p.readInt());
        ssData.teleserviceType = ssData.TeleserviceTypeFromRILInt(p.readInt());
        ssData.serviceClass = p.readInt(); // This is service class sent in the SS request.
        ssData.result = p.readInt(); // This is the result of the SS request.
        num = p.readInt();

        if (ssData.serviceType.isTypeCF() &&
            ssData.requestType.isTypeInterrogation()) {
            ssData.cfInfo = new CallForwardInfo[num];

            for (int i = 0; i < num; i++) {
                ssData.cfInfo[i] = new CallForwardInfo();

                ssData.cfInfo[i].status = p.readInt();
                ssData.cfInfo[i].reason = p.readInt();
                ssData.cfInfo[i].serviceClass = p.readInt();
                ssData.cfInfo[i].toa = p.readInt();
                ssData.cfInfo[i].number = p.readString();
                ssData.cfInfo[i].timeSeconds = p.readInt();

                riljLog("[SS Data] CF Info " + i + " : " +  ssData.cfInfo[i]);
            }
        } else {
            ssData.ssInfo = new int[num];
            for (int i = 0; i < num; i++) {
                ssData.ssInfo[i] = p.readInt();
                riljLog("[SS Data] SS Info " + i + " : " +  ssData.ssInfo[i]);
            }
        }

        return ssData;
    }

    @Override
    public void setLocalCallHold(int lchStatus) {
        byte[] payload = new byte[]{(byte)(lchStatus & 0x7F)};
        Rlog.d(RILJ_LOG_TAG, "setLocalCallHold: lchStatus is " + lchStatus);

        // sendOemRilRequestRaw(OEMHOOK_EVT_HOOK_SET_LOCAL_CALL_HOLD, 1, payload, null);
        Rlog.e(RILJ_LOG_TAG, "setLocalCallHold: stub!");
    }

    @Override
    public void
    getDataCallProfile(int appType, Message result) {
        Rlog.d(RILJ_LOG_TAG, "getDataCallProfile: not supported on MTK!");
        if (result != null) {
            CommandException ex = new CommandException(
                CommandException.Error.REQUEST_NOT_SUPPORTED);
            AsyncResult.forMessage(result, null, ex);
            result.sendToTarget();
        }
    }

    @Override
    public void getModemCapability(Message response) {
        Rlog.d(RILJ_LOG_TAG, "GetModemCapability");
        // sendOemRilRequestRaw(OEMHOOK_EVT_HOOK_GET_MODEM_CAPABILITY, 0, null, response);
        Rlog.w(RILJ_LOG_TAG, "GetModemCapability: not really implemented!");
        AsyncResult.forMessage(response, null, CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
        response.sendToTarget();
    }

    @Override
    public void updateStackBinding(int stack, int enable, Message response) {
        byte[] payload = new byte[]{(byte)stack,(byte)enable};
        Rlog.d(RILJ_LOG_TAG, "UpdateStackBinding: on Stack: " + stack +
                ", enable/disable: " + enable);

        // sendOemRilRequestRaw(OEMHOOK_EVT_HOOK_UPDATE_SUB_BINDING, 2, payload, response);
        Rlog.e(RILJ_LOG_TAG, "UpdateStackBinding: stub!");
    }

    @Override
    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message response) {
        riljLog("RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: not sending on MTK!");
        AsyncResult.forMessage(response, null, CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
        response.sendToTarget();
    }

    @Override
    public void setCdmaBroadcastConfig(CdmaSmsBroadcastConfigInfo[] configs, Message response) {
        riljLog("RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: not sending on MTK!");
        AsyncResult.forMessage(response, null, CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
        response.sendToTarget();
    }

    @Override
    public void
    startDtmf(char c, Message result) {
        /* M: call control part start */
        /* DTMF request will be ignored when the count of requests reaches 32 */
        synchronized (mDtmfReqQueue) {
            if (!mDtmfReqQueue.hasSendChldRequest() && mDtmfReqQueue.size() < mDtmfReqQueue.MAXIMUM_DTMF_REQUEST) {
                if (!mDtmfReqQueue.isStart()) {
                    RILRequest rr = RILRequest.obtain(RIL_REQUEST_DTMF_START, result);

                    rr.mParcel.writeString(Character.toString(c));
                    mDtmfReqQueue.start();
                    mDtmfReqQueue.add(rr);
                    if (mDtmfReqQueue.size() == 1) {
                        riljLog("send start dtmf");
                        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                        send(rr);
                    }
                } else {
                    riljLog("DTMF status conflict, want to start DTMF when status is " + mDtmfReqQueue.isStart());
                }
            }
        }
        /* M: call control part end */
    }
    @Override
    public void
    stopDtmf(Message result) {
        /* M: call control part start */
        /* DTMF request will be ignored when the count of requests reaches 32 */
        synchronized (mDtmfReqQueue) {
            if (!mDtmfReqQueue.hasSendChldRequest() && mDtmfReqQueue.size() < mDtmfReqQueue.MAXIMUM_DTMF_REQUEST) {
                if (mDtmfReqQueue.isStart()) {
                    RILRequest rr = RILRequest.obtain(RIL_REQUEST_DTMF_STOP, result);

                    mDtmfReqQueue.stop();
                    mDtmfReqQueue.add(rr);
                    if (mDtmfReqQueue.size() == 1) {
                        riljLog("send stop dtmf");
                        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                        send(rr);
                    }
                } else {
                    riljLog("DTMF status conflict, want to start DTMF when status is " + mDtmfReqQueue.isStart());
                }
            }
        }
        /* M: call control part end */
    }

    @Override
    public void
    switchWaitingOrHoldingAndActive (Message result) {
        RILRequest rr
                = RILRequest.obtain(
                        RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE,
                                        result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }

    @Override
    public void
    conference (Message result) {

        /* M: call control part start */
        /*
        if (MobileManagerUtils.isSupported()) {
            if (!checkMoMSSubPermission(SubPermissions.MAKE_CONFERENCE_CALL)) {
                return;
            }
        }
        */
        /* M: call control part end */

        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CONFERENCE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }

    @Override
    public void
    separateConnection (int gsmIndex, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEPARATE_CONNECTION, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                            + " " + gsmIndex);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(gsmIndex);

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }

    @Override
    public void
    explicitCallTransfer (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_EXPLICIT_CALL_TRANSFER, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }

    // all that C&P just for responseOperator overriding?
    @Override
    protected RILRequest
    processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;

        serial = p.readInt();
        error = p.readInt();

        RILRequest rr;

        rr = findAndRemoveRequestFromList(serial);

        if (rr == null) {
            Rlog.w(RILJ_LOG_TAG, "Unexpected solicited response! sn: "
                            + serial + " error: " + error);
            return null;
        }

        /* M: call control part start */
        /* DTMF request will be ignored when the count of requests reaches 32 */
        if ((rr.mRequest == RIL_REQUEST_DTMF_START) ||
            (rr.mRequest == RIL_REQUEST_DTMF_STOP)) {
            synchronized (mDtmfReqQueue) {
                mDtmfReqQueue.remove(rr);
                riljLog("remove first item in dtmf queue done, size = " + mDtmfReqQueue.size());
                if (mDtmfReqQueue.size() > 0) {
                    RILRequest rr2 = mDtmfReqQueue.get();
                    if (RILJ_LOGD) riljLog(rr2.serialString() + "> " + requestToString(rr2.mRequest));
                    send(rr2);
                } else {
                    if (mDtmfReqQueue.getPendingRequest() != null) {
                        riljLog("send pending switch request");
                        send(mDtmfReqQueue.getPendingRequest());
                        mDtmfReqQueue.setSendChldRequest();
                        mDtmfReqQueue.setPendingRequest(null);
                    }
                }
            }
        }
        /* M: call control part end */
        Object ret = null;

        if ((rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS) ||
            (rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT)) {
            // mGetAvailableNetworkDoneRegistrant.notifyRegistrants();
            Rlog.e(RILJ_LOG_TAG, "mGetAvailableNetworkDoneRegistrant.notifyRegistrants(); -- not implemented!");
        }

        /* ALPS00799783 START */
        if (rr.mRequest == RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE) {
            if ((error != 0) && (mPreviousPreferredType != -1)) {
                riljLog("restore mPreferredNetworkType from " + mPreferredNetworkType + " to " + mPreviousPreferredType);
                mPreferredNetworkType = mPreviousPreferredType;
            }
            mPreviousPreferredType = -1; //reset
        }
        /* ALPS00799783 END */

        /* M: call control part start */
        if (rr.mRequest == RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE ||
            rr.mRequest == RIL_REQUEST_CONFERENCE ||
            rr.mRequest == RIL_REQUEST_SEPARATE_CONNECTION ||
            rr.mRequest == RIL_REQUEST_EXPLICIT_CALL_TRANSFER) {
            riljLog("clear mIsSendChldRequest");
            mDtmfReqQueue.resetSendChldRequest();
        }
        /* M: call control part end */

        if (error == 0 || p.dataAvail() > 0) {

            /* Convert RIL_REQUEST_GET_MODEM_VERSION back */
            if (SystemProperties.get("ro.cm.device").indexOf("e73") == 0 &&
                  rr.mRequest == 220) {
                rr.mRequest = RIL_REQUEST_BASEBAND_VERSION;
            }

            // either command succeeds or command fails but with data payload
            try {switch (rr.mRequest) {
            /*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: ret = \2(p); break;/'
             */
            case RIL_REQUEST_GET_SIM_STATUS: ret =  responseIccCardStatus(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK2: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_DEPERSONALIZATION_CODE: ret =  responseInts(p); break;
            case RIL_REQUEST_GET_CURRENT_CALLS: ret =  responseCallList(p); break;
            case RIL_REQUEST_DIAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMSI: ret =  responseString(p); break;
            case RIL_REQUEST_HANGUP: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: ret = responseVoid(p); break;
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CONFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_UDUB: ret =  responseVoid(p); break;
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_SIGNAL_STRENGTH: ret =  responseSignalStrength(p); break;
            case RIL_REQUEST_VOICE_REGISTRATION_STATE: ret =  responseStrings(p); break;
            case RIL_REQUEST_DATA_REGISTRATION_STATE: ret =  responseStrings(p); break;
            case RIL_REQUEST_OPERATOR: ret =  responseOperator(p); break;
            case RIL_REQUEST_RADIO_POWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE: ret =  responseSMS(p); break;
            case RIL_REQUEST_SETUP_DATA_CALL: ret =  responseSetupDataCall(p); break;
            case RIL_REQUEST_SIM_IO: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_SEND_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_CANCEL_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_CLIR: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CLIR: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: ret =  responseCallForward(p); break;
            case RIL_REQUEST_SET_CALL_FORWARD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_WAITING: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CALL_WAITING: ret =  responseVoid(p); break;
            case RIL_REQUEST_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMEI: ret =  responseString(p); break;
            case RIL_REQUEST_GET_IMEISV: ret =  responseString(p); break;
            case RIL_REQUEST_ANSWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DEACTIVATE_DATA_CALL: ret = responseDeactivateDataCall(p); break; //VoLTE
            case RIL_REQUEST_QUERY_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : ret =  responseOperatorInfos(p); break;
            case RIL_REQUEST_ABORT_QUERY_AVAILABLE_NETWORKS: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF_START: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF_STOP: ret =  responseVoid(p); break;
            case RIL_REQUEST_BASEBAND_VERSION: ret =  responseString(p); break;
            case RIL_REQUEST_SEPARATE_CONNECTION: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_MUTE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_MUTE: ret =  responseInts(p); break;
            case RIL_REQUEST_QUERY_CLIP: ret =  responseInts(p); break;
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_DATA_CALL_LIST: ret =  responseDataCallList(p); break;
            case RIL_REQUEST_RESET_RADIO: ret =  responseVoid(p); break;
            case RIL_REQUEST_OEM_HOOK_RAW: ret =  responseRaw(p); break;
            case RIL_REQUEST_OEM_HOOK_STRINGS: ret =  responseStrings(p); break;
            case RIL_REQUEST_SCREEN_STATE: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_WRITE_SMS_TO_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_DELETE_SMS_ON_SIM: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_BAND_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_STK_GET_PROFILE: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SET_PROFILE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: ret =  responseGetPreferredNetworkType(p); break;
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: ret = responseCellList(p); break;
            case RIL_REQUEST_SET_LOCATION_UPDATES: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_TTY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_TTY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_FLASH: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BURST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: ret =  responseGmsBroadcastConfig(p); break;
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: ret =  responseCdmaBroadcastConfig(p); break;
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SUBSCRIPTION: ret =  responseStrings(p); break;
            /*ret = responseInts(p);RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM modify for UIM sms cache*/
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: ret =  responseStrings(p); break;
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: ret =  responseVoid(p); break;
            case RIL_REQUEST_DEVICE_IDENTITY: ret =  responseStrings(p); break;
            case RIL_REQUEST_GET_SMSC_ADDRESS: ret = responseString(p); break;
            case RIL_REQUEST_SET_SMSC_ADDRESS: ret = responseVoid(p); break;
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: ret = responseVoid(p); break;
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: ret = responseVoid(p); break;
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE: ret =  responseInts(p); break;
            case RIL_REQUEST_ISIM_AUTHENTICATION:
                if (SystemProperties.get("ro.mtk_tc1_feature").equals("1"))
                	ret =  responseStringEncodeBase64(p);
                else
                	ret =  responseString(p);
                break;
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU: ret = responseVoid(p); break;
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS: ret = responseICC_IO(p); break;
            case RIL_REQUEST_VOICE_RADIO_TECH: ret = responseInts(p); break;
            case RIL_REQUEST_GET_CELL_INFO_LIST: ret = responseCellInfoList(p); break;
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_INITIAL_ATTACH_APN: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_DATA_PROFILE: ret = responseVoid(p); break;
            case RIL_REQUEST_IMS_REGISTRATION_STATE: ret = responseInts(p); break;
            case RIL_REQUEST_IMS_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_SIM_OPEN_CHANNEL: ret  = responseInts(p); break;
            case RIL_REQUEST_SIM_CLOSE_CHANNEL: ret  = responseVoid(p); break;
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL: ret = responseICC_IO(p); break;
            case RIL_REQUEST_NV_READ_ITEM: ret = responseString(p); break;
            case RIL_REQUEST_NV_WRITE_ITEM: ret = responseVoid(p); break;
            case RIL_REQUEST_NV_WRITE_CDMA_PRL: ret = responseVoid(p); break;
            case RIL_REQUEST_NV_RESET_CONFIG: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION: ret = responseVoid(p); break;
            case RIL_REQUEST_ALLOW_DATA: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_HARDWARE_CONFIG: ret = responseHardwareConfig(p); break;
            case RIL_REQUEST_SIM_AUTHENTICATION: ret =  responseICC_IOBase64(p); break;
            case RIL_REQUEST_SHUTDOWN: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_RADIO_CAPABILITY: ret =  responseRadioCapability(p); break;
            case RIL_REQUEST_SET_RADIO_CAPABILITY: ret =  responseRadioCapability(p); break;
            /// M: CC010: Add RIL interface @{
            case RIL_REQUEST_HANGUP_ALL: ret =  responseVoid(p); break;
            case RIL_REQUEST_FORCE_RELEASE_CALL: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_CALL_INDICATION: ret = responseVoid(p); break;
            case RIL_REQUEST_EMERGENCY_DIAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_ECC_SERVICE_CATEGORY: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_ECC_LIST: ret = responseVoid(p); break;
            /// @}
            /// M: CC077: 2/3G CAPABILITY_HIGH_DEF_AUDIO @{
            case RIL_REQUEST_SET_SPEECH_CODEC_INFO: ret = responseVoid(p); break;
            /// @}
            /// M: For 3G VT only @{
            case RIL_REQUEST_VT_DIAL: ret = responseVoid(p); break;
            case RIL_REQUEST_VOICE_ACCEPT: ret = responseVoid(p); break;
            case RIL_REQUEST_REPLACE_VT_CALL: ret = responseVoid(p); break;
            /// @}
            /// M: IMS feature. @{
            case RIL_REQUEST_ADD_IMS_CONFERENCE_CALL_MEMBER: responseString(p); break;
            case RIL_REQUEST_REMOVE_IMS_CONFERENCE_CALL_MEMBER: responseString(p); break;
            case RIL_REQUEST_DIAL_WITH_SIP_URI: ret = responseVoid(p); break;
            case RIL_REQUEST_RESUME_CALL: ret = responseVoid(p); break;
            case RIL_REQUEST_HOLD_CALL: ret = responseVoid(p); break;
            /// @}

            //MTK-START SS
            case RIL_REQUEST_GET_COLP: ret = responseInts(p); break;
            case RIL_REQUEST_SET_COLP: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_COLR: ret = responseInts(p); break;
            //MTK-END SS

            //MTK-START SIM ME lock
            case RIL_REQUEST_QUERY_SIM_NETWORK_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_SIM_NETWORK_LOCK: ret =  responseInts(p); break;
            //MTK-END SIM ME lock
            //MTK-START multiple application support
            case RIL_REQUEST_GENERAL_SIM_AUTH: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_OPEN_ICC_APPLICATION: ret = responseInts(p); break;
            case RIL_REQUEST_GET_ICC_APPLICATION_STATUS: ret = responseIccCardStatus(p); break;
            //MTK-END multiple application support
            case RIL_REQUEST_SIM_IO_EX: ret =  responseICC_IO(p); break;
            // PHB Start
            case RIL_REQUEST_QUERY_PHB_STORAGE_INFO: ret = responseInts(p); break;
            case RIL_REQUEST_WRITE_PHB_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_READ_PHB_ENTRY: ret = responsePhbEntries(p); break;
            case RIL_REQUEST_QUERY_UPB_CAPABILITY: ret = responseInts(p); break;
            case RIL_REQUEST_READ_UPB_GRP: ret = responseInts(p); break;
            case RIL_REQUEST_WRITE_UPB_GRP: ret = responseVoid(p); break;
            case RIL_REQUEST_EDIT_UPB_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_DELETE_UPB_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_READ_UPB_GAS_LIST: ret = responseStrings(p); break;
            case RIL_REQUEST_GET_PHB_STRING_LENGTH: ret = responseInts(p); break;
            case RIL_REQUEST_GET_PHB_MEM_STORAGE : ret = responseGetPhbMemStorage(p); break;
            case RIL_REQUEST_SET_PHB_MEM_STORAGE : responseVoid(p); break;
            case RIL_REQUEST_READ_PHB_ENTRY_EXT: ret = responseReadPhbEntryExt(p); break;
            case RIL_REQUEST_WRITE_PHB_ENTRY_EXT: ret = responseVoid(p); break;
            // PHB End


            /* M: network part start */
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_POL_CAPABILITY: ret = responseInts(p); break;
            case RIL_REQUEST_GET_POL_LIST: ret = responseNetworkInfoWithActs(p); break;
            case RIL_REQUEST_SET_POL_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_TRM: ret = responseInts(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT : ret =  responseOperatorInfosWithAct(p); break;
            case RIL_REQUEST_STK_EVDL_CALL_BY_AP: ret = responseVoid(p); break;

            //Femtocell (CSG) feature START
            case RIL_REQUEST_GET_FEMTOCELL_LIST: ret = responseFemtoCellInfos(p); break;
            case RIL_REQUEST_ABORT_FEMTOCELL_LIST: ret = responseVoid(p); break;
            case RIL_REQUEST_SELECT_FEMTOCELL: ret = responseVoid(p); break;
            //Femtocell (CSG) feature END
            /* M: network part end */

            case RIL_REQUEST_QUERY_MODEM_TYPE: ret = responseInts(p); break;
            case RIL_REQUEST_STORE_MODEM_TYPE: ret = responseVoid(p); break;

            // IMS
            case RIL_REQUEST_SET_IMS_ENABLE: ret = responseVoid(p); break;
            case RIL_REQUEST_SIM_GET_ATR: ret = responseString(p); break;
            // M: Fast Dormancy
            case RIL_REQUEST_SET_SCRI: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_FD_MODE: ret = responseInts(p); break;

            // MTK-START, SMS part
            case RIL_REQUEST_GET_SMS_PARAMS: ret = responseSmsParams(p); break;
            case RIL_REQUEST_SET_SMS_PARAMS: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_SMS_SIM_MEM_STATUS: ret = responseSimSmsMemoryStatus(p); break;
            case RIL_REQUEST_SET_ETWS: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_CB_CHANNEL_CONFIG_INFO: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_CB_LANGUAGE_CONFIG_INFO: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_CB_CONFIG_INFO: ret = responseCbConfig(p); break;
            case RIL_REQUEST_REMOVE_CB_MESSAGE: ret = responseVoid(p); break;
            // MTK-END, SMS part
            case RIL_REQUEST_SET_DATA_CENTRIC: ret = responseVoid(p); break;

            //VoLTE
            case RIL_REQUEST_SETUP_DEDICATE_DATA_CALL: ret = responseSetupDedicateDataCall(p); break;
            case RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL: ret = responseVoid(p); break;
            case RIL_REQUEST_MODIFY_DATA_CALL: ret = responseModifyDataCall(p); break;
            case RIL_REQUEST_ABORT_SETUP_DATA_CALL: ret = responseVoid(p); break;
            case RIL_REQUEST_PCSCF_DISCOVERY_PCO: ret=responsePcscfDiscovery(p); break;
            case RIL_REQUEST_CLEAR_DATA_BEARER: ret=responseVoid(p); break;

            /// M: SVLTE Remove access feature
            case RIL_REQUEST_CONFIG_MODEM_STATUS: ret = responseVoid(p); break;

            // M: CC33 LTE.
            case RIL_REQUEST_SET_DATA_ON_TO_MD: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE: ret = responseVoid(p); break;

            case RIL_REQUEST_BTSIM_CONNECT: ret = responseString(p); break;
            case RIL_REQUEST_BTSIM_DISCONNECT_OR_POWEROFF: ret = responseVoid(p); break;
            case RIL_REQUEST_BTSIM_POWERON_OR_RESETSIM: ret = responseString(p); break;
            case RIL_REQUEST_BTSIM_TRANSFERAPDU: ret = responseString(p); break;

            /// M: IMS VoLTE conference dial feature. @{
            case RIL_REQUEST_CONFERENCE_DIAL: ret =  responseVoid(p); break;
            /// @}
            case RIL_REQUEST_RELOAD_MODEM_TYPE: ret =  responseVoid(p); break;
            /// M: CC010: Add RIL interface @{
            case RIL_REQUEST_SET_IMS_CALL_STATUS: ret = responseVoid(p); break;
            /// @}

            /// M: CC072: Add Customer proprietary-IMS RIL interface. @{
            case RIL_REQUEST_SET_SRVCC_CALL_CONTEXT_TRANSFER: ret = responseVoid(p); break;
            case RIL_REQUEST_UPDATE_IMS_REGISTRATION_STATUS: ret = responseVoid(p); break;
            /// @}

            /* M: C2K part start */
            case RIL_REQUEST_GET_NITZ_TIME: ret = responseGetNitzTime(p); break;
            case RIL_REQUEST_QUERY_UIM_INSERTED: ret = responseInts(p); break;
            case RIL_REQUEST_SWITCH_HPF: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_AVOID_SYS: ret = responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVOID_SYS: ret = responseInts(p); break;
            case RIL_REQUEST_QUERY_CDMA_NETWORK_INFO: ret = responseStrings(p); break;
            case RIL_REQUEST_GET_LOCAL_INFO: ret =  responseInts(p); break;
            case RIL_REQUEST_UTK_REFRESH: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS: ret = responseInts(p); break;
            case RIL_REQUEST_QUERY_NETWORK_REGISTRATION: ret = responseInts(p); break;
            case RIL_REQUEST_AGPS_TCP_CONNIND: ret = responseVoid(p); break;
            case RIL_REQUEST_AGPS_SET_MPC_IPPORT: ret = responseVoid(p); break;
            case RIL_REQUEST_AGPS_GET_MPC_IPPORT: ret = responseStrings(p); break;
            case RIL_REQUEST_SET_MEID: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_ETS_DEV: ret =  responseVoid(p); break;
            case RIL_REQUEST_WRITE_MDN: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_VIA_TRM: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_ARSI_THRESHOLD: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_ACTIVE_PS_SLOT: ret = responseVoid(p); break;
            case RIL_REQUEST_CONFIRM_INTER_3GPP_IRAT_CHANGE: ret = responseVoid(p); break;
            case RIL_REQUEST_CONFIG_IRAT_MODE: ret = responseVoid(p); break;
            case RIL_REQUEST_CONFIG_EVDO_MODE: ret = responseVoid(p); break;
            case RIL_REQUEST_QUERY_UTK_MENU_FROM_MD: ret =  responseString(p); break;
            case RIL_REQUEST_QUERY_STK_MENU_FROM_MD: ret =  responseString(p); break;
            case RIL_REQUEST_DEACTIVATE_LINK_DOWN_PDN: ret = responseVoid(p); break;
            /* M: C2K part end */

            case RIL_REQUEST_MODEM_POWERON: ret =  responseVoid(p); break;
            case RIL_REQUEST_MODEM_POWEROFF: ret =  responseVoid(p); break;

            /// M: [C2K][SVLTE] Set the SVLTE RAT mode. @{
            case RIL_REQUEST_SET_SVLTE_RAT_MODE: ret =  responseVoid(p); break;
            /// M: [C2K][SVLTE] Set the SVLTE RAT mode. @}

            /// M: [C2K][IR] Support SVLTE IR feature. @{
            case RIL_REQUEST_SET_REG_SUSPEND_ENABLED: ret = responseVoid(p); break;
            case RIL_REQUEST_RESUME_REGISTRATION: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_REG_SUSPEND_ENABLED_CDMA: ret =  responseVoid(p); break;
            case RIL_REQUEST_RESUME_REGISTRATION_CDMA: ret =  responseVoid(p); break;
            /// M: [C2K][IR] Support SVLTE IR feature. @}
            case RIL_REQUEST_SET_STK_UTK_MODE: ret = responseVoid(p); break;

            case RIL_REQUEST_SWITCH_ANTENNA: ret = responseVoid(p); break;
            default:
                throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            //break;
            }} catch (Throwable tr) {
                // Exceptions here usually mean invalid RIL responses

                Rlog.w(RILJ_LOG_TAG, rr.serialString() + "< "
                        + requestToString(rr.mRequest)
                        + " exception, possible invalid RIL response", tr);

                if (rr.mResult != null) {
                    AsyncResult.forMessage(rr.mResult, null, tr);
                    rr.mResult.sendToTarget();
                }
                return rr;
            }
        }

        if (rr.mRequest == RIL_REQUEST_SHUTDOWN) {
            // Set RADIO_STATE to RADIO_UNAVAILABLE to continue shutdown process
            // regardless of error code to continue shutdown procedure.
            riljLog("Response to RIL_REQUEST_SHUTDOWN received. Error is " +
                    error + " Setting Radio State to Unavailable regardless of error.");
            setRadioState(RadioState.RADIO_UNAVAILABLE);
        }

        // Here and below fake RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED, see b/7255789.
        // This is needed otherwise we don't automatically transition to the main lock
        // screen when the pin or puk is entered incorrectly.
        switch (rr.mRequest) {
            case RIL_REQUEST_ENTER_SIM_PUK:
            case RIL_REQUEST_ENTER_SIM_PUK2:
                if (mIccStatusChangedRegistrants != null) {
                    if (RILJ_LOGD) {
                        riljLog("ON enter sim puk fakeSimStatusChanged: reg count="
                                + mIccStatusChangedRegistrants.size());
                    }
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
        }

        if (error != 0) {
            switch (rr.mRequest) {
                case RIL_REQUEST_ENTER_SIM_PIN:
                case RIL_REQUEST_ENTER_SIM_PIN2:
                case RIL_REQUEST_CHANGE_SIM_PIN:
                case RIL_REQUEST_CHANGE_SIM_PIN2:
                case RIL_REQUEST_SET_FACILITY_LOCK:
                    if (mIccStatusChangedRegistrants != null) {
                        if (RILJ_LOGD) {
                            riljLog("ON some errors fakeSimStatusChanged: reg count="
                                    + mIccStatusChangedRegistrants.size());
                        }
                        mIccStatusChangedRegistrants.notifyRegistrants();
                    }
                    break;
            }

            rr.onError(error, ret);
            return rr;
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
            + " " + retToString(rr.mRequest, ret));

        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }

        return rr;
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RIL_CONNECTED: ret = responseInts(p); break;

            // MTK cases
            case RIL_UNSOL_NEIGHBORING_CELL_INFO: ret = responseStrings(p); break;
            case RIL_UNSOL_NETWORK_INFO: ret = responseStrings(p); break;
            case RIL_UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_INVALID_SIM:  ret = responseStrings(p); break;
            case RIL_UNSOL_RESPONSE_ACMT: ret = responseInts(p); break;
            case RIL_UNSOL_IMEI_LOCK: ret = responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_MMRR_STATUS_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_STK_EVDL_CALL: ret = responseInts(p); break;
            case RIL_UNSOL_STK_CALL_CTRL: ret = responseStrings(p); break;

            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED: ret =  responseInts(p); break;
            case RIL_UNSOL_SRVCC_STATE_NOTIFY: ret = responseInts(p); break;
            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED: ret = responseHardwareConfig(p); break;
            case RIL_UNSOL_RADIO_CAPABILITY:
                    ret = responseRadioCapability(p); break;
            case RIL_UNSOL_ON_SS: ret =  responseSsData(p); break;
            case RIL_UNSOL_STK_CC_ALPHA_NOTIFY: ret =  responseStrings(p); break;
            /// M: CC010: Add RIL interface @{
            case RIL_UNSOL_CALL_FORWARDING: ret = responseInts(p); break;
            case RIL_UNSOL_CRSS_NOTIFICATION: ret = responseCrssNotification(p); break;
            case RIL_UNSOL_INCOMING_CALL_INDICATION: ret = responseStrings(p); break;
            case RIL_UNSOL_CIPHER_INDICATION: ret = responseStrings(p); break;
            case RIL_UNSOL_CNAP: ret = responseStrings(p); break;
            /// @}
            /// M: CC077: 2/3G CAPABILITY_HIGH_DEF_AUDIO @{
            case RIL_UNSOL_SPEECH_CODEC_INFO: ret =  responseInts(p); break;
            /// @}
            //MTK-START multiple application support
            case RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED: ret = responseInts(p); break;
            //MTK-END multiple application support
            case RIL_UNSOL_SIM_MISSING: ret = responseInts(p); break;
            case RIL_UNSOL_SIM_RECOVERY: ret = responseInts(p); break;
            case RIL_UNSOL_VIRTUAL_SIM_ON: ret = responseInts(p); break;
            case RIL_UNSOL_VIRTUAL_SIM_OFF: ret = responseInts(p); break;
            case RIL_UNSOL_SIM_PLUG_OUT: ret = responseVoid(p); break;
            case RIL_UNSOL_SIM_PLUG_IN: ret = responseVoid(p); break;
            case RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED: ret = responseVoid(p); break;
            case RIL_UNSOL_DATA_ALLOWED: ret = responseVoid(p); break;
            case RIL_UNSOL_PHB_READY_NOTIFICATION: ret = responseInts(p); break;
            case RIL_UNSOL_STK_SETUP_MENU_RESET: ret = responseVoid(p); break;
            // IMS
            case RIL_UNSOL_IMS_ENABLE_DONE: ret = responseVoid(p); break;
            case RIL_UNSOL_IMS_DISABLE_DONE: ret = responseVoid(p); break;
            case RIL_UNSOL_IMS_REGISTRATION_INFO: ret = responseInts(p); break;
            //VoLTE
            case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED: ret = responseSetupDedicateDataCall(p);break;
            case RIL_UNSOL_DEDICATE_BEARER_MODIFIED: ret = responseSetupDedicateDataCall(p);break;
            case RIL_UNSOL_DEDICATE_BEARER_DEACTIVATED: ret = responseInts(p);break;
            // M: Fast Dormancy
            case RIL_UNSOL_SCRI_RESULT: ret = responseInts(p); break;

            case RIL_UNSOL_RESPONSE_PLMN_CHANGED: ret = responseStrings(p); break;
            case RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED: ret = responseInts(p); break;
            //Remote SIM ME lock related APIs [Start]
            case RIL_UNSOL_MELOCK_NOTIFICATION: ret = responseInts(p); break;
            //Remote SIM ME lock related APIs [End]
            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_SUPPORT: ret = responseInts(p); break;

            /// M: IMS feature. @{
            //For updating call ids for conference call after SRVCC is done.
            case RIL_UNSOL_ECONF_SRVCC_INDICATION: ret = responseInts(p); break;
            //For updating conference call merged/added result.
            case RIL_UNSOL_ECONF_RESULT_INDICATION: ret = responseStrings(p); break;
            //For updating call mode and pau information.
            case RIL_UNSOL_CALL_INFO_INDICATION : ret = responseStrings(p); break;
            /// @}

            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO: ret = responseInts(p); break;
            case RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION:ret = responseInts(p); break;
            // M: CC33 LTE.
            case RIL_UNSOL_RAC_UPDATE: ret = responseVoid(p); break;
            case RIL_UNSOL_REMOVE_RESTRICT_EUTRAN: ret = responseVoid(p); break;

            //MTK-START for MD state change
            case RIL_UNSOL_MD_STATE_CHANGE: ret = responseInts(p); break;
            //MTK-END for MD state change

            case RIL_UNSOL_MO_DATA_BARRING_INFO: ret = responseInts(p); break;
            case RIL_UNSOL_SSAC_BARRING_INFO: ret = responseInts(p); break;

            /// M: CC071: Add Customer proprietary-IMS RIL interface. @{
            case RIL_UNSOL_EMERGENCY_BEARER_SUPPORT_NOTIFY: ret = responseInts(p); break;
            /// @}

            /* M: C2K part start*/
            case RIL_UNSOL_CDMA_CALL_ACCEPTED: ret = responseVoid(p); break;
            case RIL_UNSOL_UTK_SESSION_END: ret = responseVoid(p); break;
            case RIL_UNSOL_UTK_PROACTIVE_COMMAND: ret = responseString(p); break;
            case RIL_UNSOL_UTK_EVENT_NOTIFY: ret = responseString(p); break;
            case RIL_UNSOL_VIA_GPS_EVENT: ret = responseInts(p); break;
            case RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE: ret = responseInts(p); break;
            case RIL_UNSOL_VIA_INVALID_SIM_DETECTED: ret = responseVoid(p); break;
            /* M: C2K part end*/
            case RIL_UNSOL_ABNORMAL_EVENT: ret = responseStrings(p); break;
            case RIL_UNSOL_CDMA_CARD_TYPE: ret = responseInts(p); break;
            /// M: [C2K] for eng mode start
            case RIL_UNSOL_ENG_MODE_NETWORK_INFO:
                ret = responseStrings(p);
                unsljLog(response);
                break;
            /// M: [C2K] for eng mode end

            /// M: [C2K][IR] Support SVLTE IR feature. @{
            case RIL_UNSOL_CDMA_PLMN_CHANGED: ret = responseStrings(p); break;
            /// M: [C2K][IR] Support SVLTE IR feature. @}

            /// M: [C2K][IR][MD-IRAT] URC for GMSS RAT changed. @{
            case RIL_UNSOL_GMSS_RAT_CHANGED: ret = responseInts(p); break;
            /// M: [C2K][IR][MD-IRAT] URC for GMSS RAT changed. @}

            // MTK-START, SMS part
            // SMS ready
            case RIL_UNSOL_SMS_READY_NOTIFICATION: ret = responseVoid(p); break;
            // New SMS but phone storage is full
            case RIL_UNSOL_ME_SMS_STORAGE_FULL: ret = responseVoid(p); break;
            // ETWS primary notification
            case RIL_UNSOL_RESPONSE_ETWS_NOTIFICATION: ret = responseEtwsNotification(p); break;
            // MTK-END, SMS part

            /// M: [C2K] For ps type changed.
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_TYPE_CHANGED: ret = responseInts(p); break;

            ///M: [C2K][MD IRAT] start @{
            case RIL_UNSOL_INTER_3GPP_IRAT_STATE_CHANGE:
                riljLog(" RIL_UNSOL_INTER_3GPP_IRAT_STATE_CHANGE...");
                ret = responseIratStateChange(p);
                break;
            /// }@ [C2K][MD IRAT] end
            // M: [C2K] AP IRAT start.
            case RIL_UNSOL_LTE_BG_SEARCH_STATUS: ret = responseInts(p); break;
            case RIL_UNSOL_LTE_EARFCN_INFO: ret = responseInts(p); break;
            // M: [C2K] AP IRAT end.
            case RIL_UNSOL_IMSI_REFRESH_DONE: ret = responseVoid(p); break;
            case RIL_UNSOL_CDMA_IMSI_READY: ret = responseVoid(p); break;
            // M: Notify RILJ that the AT+EUSIM was received
            case RIL_UNSOL_EUSIM_READY: ret = responseVoid(p); break;
            /// M: For 3G VT only @{
            case RIL_UNSOL_VT_STATUS_INFO: ret = responseInts(p); break;
            case RIL_UNSOL_VT_RING_INFO: ret = responseVoid(p); break;
            /// @}
            // M: Notify RILJ that call fade happened
            case RIL_UNSOL_CDMA_SIGNAL_FADE: ret = responseInts(p); break;
            // M: Notify RILJ that the AT+EFNM was received
            case RIL_UNSOL_CDMA_TONE_SIGNALS: ret = responseInts(p); break;
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p);
                return;
        }

        // To avoid duplicating code from RIL.java, we rewrite some response codes to fit
        // AOSP's one (when they do the same effect)
        boolean rewindAndReplace = false;
        int newResponseCode = 0;

        switch (response) {
            case RIL_UNSOL_RIL_CONNECTED: {
                if (RILJ_LOGD) unsljLogRet(response, ret);

                getRadioCapability(mSupportedRafHandler.obtainMessage());
                // Set ecc list before MO call
                if  (TelephonyManager.getDefault().getMultiSimConfiguration() == TelephonyManager.MultiSimVariants.DSDA
                        || mInstanceId == 0) {
                    setEccList();
                }

                // Initial conditions
                //setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                setCdmaSubscriptionSource(mCdmaSubscription, null);
                setCellInfoListRate(Integer.MAX_VALUE, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                //[ALPS01810775,ALPS01868743]-Start
                //"isScreenOn" removed and replaced by mDefaultDisplayState
                //sendScreenState(isScreenOn);
                if (mDefaultDisplayState == Display.STATE_ON){
                    sendScreenState(true);
                } else if (mDefaultDisplayState == Display.STATE_OFF){
                    sendScreenState(false);
                } else {
                    riljLog("not setScreenState mDefaultDisplayState="
                            + mDefaultDisplayState);
                }
                //[ALPS01810775,ALPS01868743]-End

                // SVLTE remote SIM Access
                //if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() && !CdmaFeatureOptionUtils.isC2KWorldPhoneP2Support()) {
                //    configModemRemoteSimAccess();
                //}
                break;
            }

            case RIL_UNSOL_NEIGHBORING_CELL_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mNeighboringInfoRegistrants != null) {
                    mNeighboringInfoRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_NETWORK_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mNetworkInfoRegistrants != null) {
                    mNetworkInfoRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_RADIO_CAPABILITY:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mPhoneRadioCapabilityChangedRegistrants != null) {
                    mPhoneRadioCapabilityChangedRegistrants.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                 }
                 break;

            case RIL_UNSOL_PHB_READY_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mPhbReadyRegistrants != null) {
                    mPhbReadyRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_EVDL_CALL:
                Rlog.e(RILJ_LOG_TAG, "RIL_UNSOL_STK_EVDL_CALL: stub!");
                /*
                if (false == SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                    if (RILJ_LOGD) unsljLogvRet(response, ret);
                    if (mStkEvdlCallRegistrant != null) {
                        mStkEvdlCallRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                    }
                }
                */
                break;

            case RIL_UNSOL_STK_CALL_CTRL:
                if (RILJ_LOGD) {
                    unsljLogvRet(response, ret);
                }
                if (mStkCallCtrlRegistrant != null) {
                    mStkCallCtrlRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_SETUP_MENU_RESET:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                Rlog.e(RILJ_LOG_TAG, "RIL_UNSOL_STK_SETUP_MENU_RESET: stub!");
                /*
                if (mStkSetupMenuResetRegistrant != null) {
                    mStkSetupMenuResetRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                */
                break;

            //MTK-START multiple application support
            case RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED: {
                if (RILJ_LOGD) unsljLog(response);
                if (mSessionChangedRegistrants != null) {
                    mSessionChangedRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            }
            //MTK-END multiple application support

            case RIL_UNSOL_SIM_MISSING:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimMissing != null) {
                    mSimMissing.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_SIM_RECOVERY:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimRecovery != null) {
                    mSimRecovery.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_VIRTUAL_SIM_ON:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mVirtualSimOn != null) {
                    mVirtualSimOn.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_VIRTUAL_SIM_OFF:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mVirtualSimOff != null) {
                    mVirtualSimOff.notifyRegistrants(
                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_SIM_PLUG_OUT:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimPlugOutRegistrants != null) {
                    mSimPlugOutRegistrants.notifyRegistrants(
                        new AsyncResult(null, ret, null));
                }
                mCfuReturnValue = null;
                break;

            case RIL_UNSOL_SIM_PLUG_IN:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimPlugInRegistrants != null) {
                    mSimPlugInRegistrants.notifyRegistrants(
                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED:
                if (RILJ_LOGD) unsljLog(response);
                if (mCommonSlotNoChangedRegistrants != null) {
                    mCommonSlotNoChangedRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
                }
                break;

            case RIL_UNSOL_RESPONSE_PLMN_CHANGED:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mPlmnChangeNotificationRegistrant != null) {
                    mPlmnChangeNotificationRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                } else {
                    mEcopsReturnValue = ret;
                }
                break;

            case RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRegistrationSuspendedRegistrant != null) {
                    mRegistrationSuspendedRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                } else {
                    mEmsrReturnValue = ret;
                }
                break;

            // M: Fast Dormancy
            case RIL_UNSOL_SCRI_RESULT:
                Integer scriResult = (((int[]) ret)[0]);
                riljLog("s:" + scriResult + ":" + (((int[]) ret)[0]));
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mScriResultRegistrant != null) {
                   mScriResultRegistrant.notifyRegistrant(new AsyncResult(null, scriResult, null));
                }
                break;

            /*
            case RIL_UNSOL_CALL_PROGRESS_INFO:
                rewindAndReplace = true;
                newResponseCode = RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED;
                break;
                */

            case RIL_UNSOL_INCOMING_CALL_INDICATION:
                setCallIndication((String[])ret);
                rewindAndReplace = true;
                newResponseCode = RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED;
                break;

            case RIL_UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                mPsNetworkStateRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
            break;

            // MTK-START, SMS part
            // SMS ready notification
            case RIL_UNSOL_SMS_READY_NOTIFICATION:
                if (RILJ_LOGD) unsljLog(response);

                if (mSmsReadyRegistrants.size() != 0) {
                    mSmsReadyRegistrants.notifyRegistrants();
                } else {
                    // Phone process is not ready and cache it then wait register to notify
                    if (RILJ_LOGD) Rlog.d(RILJ_LOG_TAG, "Cache sms ready event");
                    mIsSmsReady = true;
                }
                break;

            // New SMS but phone storage is full
            case RIL_UNSOL_ME_SMS_STORAGE_FULL:
                if (RILJ_LOGD) unsljLog(response);
                if (mMeSmsFullRegistrant != null) {
                    mMeSmsFullRegistrant.notifyRegistrant();
                }
                break;

            // ETWS primary notification
            case RIL_UNSOL_RESPONSE_ETWS_NOTIFICATION:
                if (RILJ_LOGD) unsljLog(response);
                if (mEtwsNotificationRegistrant != null) {
                    mEtwsNotificationRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;
            // MTK-END, SMS part

            case RIL_UNSOL_DATA_ALLOWED:
                if (RILJ_LOGD) unsljLog(response);
                if (mDataAllowedRegistrants != null) {
                    mDataAllowedRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
                }
                break;

            case RIL_UNSOL_IMS_REGISTRATION_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mImsRegistrationInfoRegistrants != null) {
                    mImsRegistrationInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            //Remote SIM ME lock related APIs [Start]
            case RIL_UNSOL_MELOCK_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mMelockRegistrants != null) {
                    mMelockRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;
            //Remote SIM ME lock related APIs [End]

            case RIL_UNSOL_IMS_ENABLE_DONE:
                if (RILJ_LOGD) unsljLog(response);
                if (mImsEnableRegistrants != null) {
                    mImsEnableRegistrants.notifyRegistrants();
                }
                break;

            case RIL_UNSOL_IMS_DISABLE_DONE:
                if (RILJ_LOGD) unsljLog(response);
                if (mImsDisableRegistrants != null) {
                    mImsDisableRegistrants.notifyRegistrants();
                }
                break;

            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_SUPPORT:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mEpsNetworkFeatureSupportRegistrants != null) {
                    mEpsNetworkFeatureSupportRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            /// M: IMS feature. @{
            //For updating call ids for conference call after SRVCC is done.
            case RIL_UNSOL_ECONF_SRVCC_INDICATION:
                if (RILJ_LOGD) unsljLog(response);
                if (mEconfSrvccRegistrants != null) {
                    mEconfSrvccRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            //For updating conference call merged/added result.
            case RIL_UNSOL_ECONF_RESULT_INDICATION:
                if (RILJ_LOGD) unsljLog(response);
                if (mEconfResultRegistrants != null) {
                	 riljLog("Notify ECONF result");
                	 String[] econfResult = (String[])ret;
                	 riljLog("ECONF result = " + econfResult[3]);
                	 mEconfResultRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            //For updating call mode and pau information.
            case RIL_UNSOL_CALL_INFO_INDICATION :
                if (RILJ_LOGD) unsljLog(response);
                if (mCallInfoRegistrants != null) {
                   mCallInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            /// @}

            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mEpsNetworkFeatureInfoRegistrants != null) {
                   mEpsNetworkFeatureInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION:
                if (RILJ_LOGD) unsljLog(response);
                if (mSrvccHandoverInfoIndicationRegistrants != null) {
                    mSrvccHandoverInfoIndicationRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            // IMS
            //VoLTE
            case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if(mDedicateBearerActivatedRegistrant != null) {
                    mDedicateBearerActivatedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_DEDICATE_BEARER_MODIFIED:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if(mDedicateBearerModifiedRegistrant != null) {
                    mDedicateBearerModifiedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_DEDICATE_BEARER_DEACTIVATED:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if(mDedicateBearerDeactivatedRegistrant != null) {
                    mDedicateBearerDeactivatedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            //MTK-START for MD state change
            case RIL_UNSOL_MD_STATE_CHANGE:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                break;
            //MTK-END for MD state change

            case RIL_UNSOL_MO_DATA_BARRING_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mMoDataBarringInfoRegistrants != null) {
                    mMoDataBarringInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_SSAC_BARRING_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mSsacBarringInfoRegistrants != null) {
                    mSsacBarringInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            /// M: CC071: Add Customer proprietary-IMS RIL interface. @[
            case RIL_UNSOL_EMERGENCY_BEARER_SUPPORT_NOTIFY:
                if (RILJ_LOGD) unsljLog(response);
                if (mEmergencyBearerSupportInfoRegistrants != null) {
                    mEmergencyBearerSupportInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            /// @}

            // M: CC33 LTE.
            case RIL_UNSOL_RAC_UPDATE:
                if (RILJ_LOGD) unsljLog(response);
                mRacUpdateRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
                break;
            case RIL_UNSOL_REMOVE_RESTRICT_EUTRAN:
                if (RILJ_LOGD) unsljLog(response);
                mRemoveRestrictEutranRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
                break;
            /* M: C2K part start */
            case RIL_UNSOL_CDMA_CALL_ACCEPTED:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }

                if (mAcceptedRegistrant != null) {
                    mAcceptedRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_UTK_SESSION_END:
                if (RILJ_LOGD) {
                    unsljLog(response);
                }

                if (mUtkSessionEndRegistrant != null) {
                    mUtkSessionEndRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_UTK_PROACTIVE_COMMAND:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }

                if (mUtkProCmdRegistrant != null) {
                    mUtkProCmdRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_UTK_EVENT_NOTIFY:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mUtkEventRegistrant != null) {
                    mUtkEventRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIA_GPS_EVENT:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mViaGpsEvent != null) {
                    mViaGpsEvent.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mNetworkTypeChangedRegistrant != null) {
                    mNetworkTypeChangedRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIA_INVALID_SIM_DETECTED:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mInvalidSimDetectedRegistrant != null) {
                    mInvalidSimDetectedRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            /* M: C2K part end*/
            case RIL_UNSOL_ABNORMAL_EVENT:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mAbnormalEventRegistrant != null) {
                    mAbnormalEventRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_CDMA_CARD_TYPE:
                if (RILJ_LOGD) {
                    unsljLogvRet(response, ret);
                }
                if (mCdmaCardTypeRegistrants != null) {
                    mCdmaCardTypeValue = ret;
                    mCdmaCardTypeRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            /// M:[C2K] for eng mode start
            case RIL_UNSOL_ENG_MODE_NETWORK_INFO:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mEngModeNetworkInfoRegistrant != null) {
                    mEngModeNetworkInfoRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            /// M:[C2K] for eng mode end

            /// M: [C2K][IR] Support SVLTE IR feature. @{
            case RIL_UNSOL_CDMA_PLMN_CHANGED:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                String mccmnc = "";
                if (ret != null && ret instanceof String[]) {
                    String s[] = (String[]) ret;
                    if (s.length >= 2) {
                        mccmnc = s[0] + s[1];
                    }
                }
                riljLog("mccmnc changed mccmnc=" + mccmnc);
                mMccMncChangeRegistrants.notifyRegistrants(new AsyncResult(null, mccmnc, null));
                break;
            /// M: [C2K][IR] Support SVLTE IR feature. @}

            /// M: [C2K][IR][MD-IRAT] URC for GMSS RAT changed. @{
            case RIL_UNSOL_GMSS_RAT_CHANGED:
                if (RILJ_LOGD) {
                    unsljLogvRet(response, ret);
                }
                int[] rat = (int[]) ret;
                riljLog("Notify RIL_UNSOL_GMSS_RAT_CHANGED result rat = " + rat);
                if (mGmssRatChangedRegistrant != null) {
                    mGmssRatChangedRegistrant.notifyRegistrants(
                            new AsyncResult(null, rat, null));
                }
                break;
            /// M: [C2K][IR][MD-IRAT] URC for GMSS RAT changed. @}

            /// M: [C2K] for ps type changed. @{
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_TYPE_CHANGED:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }

                if (mDataNetworkTypeChangedRegistrant != null) {
                    mDataNetworkTypeChangedRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            /// @}
            ///M: [C2K][MD IRAT] start @{
            case RIL_UNSOL_INTER_3GPP_IRAT_STATE_CHANGE:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                mIratStateChangeRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                break;
            /// @} [C2K][MD IRAT] end
            // M: [C2K][AP IRAT] start.
            case RIL_UNSOL_LTE_BG_SEARCH_STATUS:
                if (RILJ_LOGD) {
                    unsljLog(response);
                }
                if (mLteBgSearchStatusRegistrant != null) {
                    mLteBgSearchStatusRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_LTE_EARFCN_INFO:
                if (RILJ_LOGD) {
                    unsljLog(response);
                }
                if (mLteEarfcnInfoRegistrant != null) {
                    mLteEarfcnInfoRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            // M: [C2K][AP IRAT] end.
            case RIL_UNSOL_IMSI_REFRESH_DONE:
                if (RILJ_LOGD) {
                    unsljLog(response);
                }
                if (mImsiRefreshDoneRegistrant != null) {
                    mImsiRefreshDoneRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_CDMA_IMSI_READY:
                if (RILJ_LOGD) {
                    unsljLog(response);
                }
                if (mCdmaImsiReadyRegistrant != null) {
                    mCdmaImsiReadyRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_EUSIM_READY:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                mIsEusimReady = true;
                if (mEusimReady != null) {
                    mEusimReady.notifyRegistrants(new AsyncResult(null, null, null));
                    if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                        if ((mInstanceId == 0) || (mInstanceId == 10)) {
                            SystemProperties.set(PROPERTY_RIL_CARD_TYPE_SET, "1");
                            riljLog("set gsm.ril.cardtypeset to 1");
                        } else if((mInstanceId == 1) || (mInstanceId == 11)) {
                            SystemProperties.set(PROPERTY_RIL_CARD_TYPE_SET_2, "1");
                            riljLog("set gsm.ril.cardtypeset.2 to 1");
                        } else {
                            riljLog("not set cardtypeset mInstanceId=" + mInstanceId);
                        }
                    }
                }
                break;
            /// M: For 3G VT only @{
            case RIL_UNSOL_VT_STATUS_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mVtStatusInfoRegistrants != null) {
                    mVtStatusInfoRegistrants.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_VT_RING_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mVtRingRegistrants != null) {
                    mVtRingRegistrants.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            /// @}
            // M: Notify RILJ that call fade happened
            case RIL_UNSOL_CDMA_SIGNAL_FADE:
                if (RILJ_LOGD) {
                    unsljLogvRet(response, ret);
                }
                if (mCdmaSignalFadeRegistrant != null) {
                    mCdmaSignalFadeRegistrant.notifyRegistrant(
                        new AsyncResult(null, ret, null));
                }
                break;
            // M: Notify RILJ that the AT+EFNM was received
            case RIL_UNSOL_CDMA_TONE_SIGNALS:
                if (RILJ_LOGD) {
                unsljLogvRet(response, ret);
                }
                if (mCdmaToneSignalsRegistrant != null) {
                    mCdmaToneSignalsRegistrant.notifyRegistrant(
                        new AsyncResult(null, ret, null));
                }
                break;

            default:
                Rlog.i(RILJ_LOG_TAG, "Unprocessed unsolicited known MTK response: " + response);
        }

        if (rewindAndReplace) {
            Rlog.w(RILJ_LOG_TAG, "Rewriting MTK unsolicited response " + response + " to " + newResponseCode);

            // Rewrite
            p.setDataPosition(dataPosition);
            p.writeInt(newResponseCode);

            // And rewind again in front
            p.setDataPosition(dataPosition);

            super.processUnsolicited(p);
        }
    }

	static String
    requestToString(int request) {
/*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch(request) {
            case RIL_REQUEST_GET_SIM_STATUS: return "GET_SIM_STATUS";
            case RIL_REQUEST_ENTER_SIM_PIN: return "ENTER_SIM_PIN";
            case RIL_REQUEST_ENTER_SIM_PUK: return "ENTER_SIM_PUK";
            case RIL_REQUEST_ENTER_SIM_PIN2: return "ENTER_SIM_PIN2";
            case RIL_REQUEST_ENTER_SIM_PUK2: return "ENTER_SIM_PUK2";
            case RIL_REQUEST_CHANGE_SIM_PIN: return "CHANGE_SIM_PIN";
            case RIL_REQUEST_CHANGE_SIM_PIN2: return "CHANGE_SIM_PIN2";
            case RIL_REQUEST_ENTER_DEPERSONALIZATION_CODE: return "ENTER_DEPERSONALIZATION_CODE";
            case RIL_REQUEST_GET_CURRENT_CALLS: return "GET_CURRENT_CALLS";
            case RIL_REQUEST_DIAL: return "DIAL";
            case RIL_REQUEST_GET_IMSI: return "GET_IMSI";
            case RIL_REQUEST_HANGUP: return "HANGUP";
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: return "HANGUP_WAITING_OR_BACKGROUND";
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: return "HANGUP_FOREGROUND_RESUME_BACKGROUND";
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: return "REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE";
            case RIL_REQUEST_CONFERENCE: return "CONFERENCE";
            case RIL_REQUEST_UDUB: return "UDUB";
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: return "LAST_CALL_FAIL_CAUSE";
            case RIL_REQUEST_SIGNAL_STRENGTH: return "SIGNAL_STRENGTH";
            case RIL_REQUEST_VOICE_REGISTRATION_STATE: return "VOICE_REGISTRATION_STATE";
            case RIL_REQUEST_DATA_REGISTRATION_STATE: return "DATA_REGISTRATION_STATE";
            case RIL_REQUEST_OPERATOR: return "OPERATOR";
            case RIL_REQUEST_RADIO_POWER: return "RADIO_POWER";
            case RIL_REQUEST_DTMF: return "DTMF";
            case RIL_REQUEST_SEND_SMS: return "SEND_SMS";
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE: return "SEND_SMS_EXPECT_MORE";
            case RIL_REQUEST_SETUP_DATA_CALL: return "SETUP_DATA_CALL";
            case RIL_REQUEST_SIM_IO: return "SIM_IO";
            case RIL_REQUEST_SEND_USSD: return "SEND_USSD";
            /* M: SS part */
            ///M: For query CNAP
            case RIL_REQUEST_SEND_CNAP: return "SEND_CNAP";
            /* M: SS part end */
            case RIL_REQUEST_CANCEL_USSD: return "CANCEL_USSD";
            case RIL_REQUEST_GET_CLIR: return "GET_CLIR";
            case RIL_REQUEST_SET_CLIR: return "SET_CLIR";
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: return "QUERY_CALL_FORWARD_STATUS";
            case RIL_REQUEST_SET_CALL_FORWARD: return "SET_CALL_FORWARD";
            case RIL_REQUEST_QUERY_CALL_WAITING: return "QUERY_CALL_WAITING";
            case RIL_REQUEST_SET_CALL_WAITING: return "SET_CALL_WAITING";
            case RIL_REQUEST_SMS_ACKNOWLEDGE: return "SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GET_IMEI: return "GET_IMEI";
            case RIL_REQUEST_GET_IMEISV: return "GET_IMEISV";
            case RIL_REQUEST_ANSWER: return "ANSWER";
            case RIL_REQUEST_DEACTIVATE_DATA_CALL: return "DEACTIVATE_DATA_CALL";
            case RIL_REQUEST_QUERY_FACILITY_LOCK: return "QUERY_FACILITY_LOCK";
            case RIL_REQUEST_SET_FACILITY_LOCK: return "SET_FACILITY_LOCK";
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD: return "CHANGE_BARRING_PASSWORD";
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: return "QUERY_NETWORK_SELECTION_MODE";
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: return "SET_NETWORK_SELECTION_AUTOMATIC";
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: return "SET_NETWORK_SELECTION_MANUAL";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : return "QUERY_AVAILABLE_NETWORKS ";
            case RIL_REQUEST_ABORT_QUERY_AVAILABLE_NETWORKS : return "ABORT_QUERY_AVAILABLE_NETWORKS";
            case RIL_REQUEST_DTMF_START: return "DTMF_START";
            case RIL_REQUEST_DTMF_STOP: return "DTMF_STOP";
            case RIL_REQUEST_BASEBAND_VERSION: return "BASEBAND_VERSION";
            case RIL_REQUEST_SEPARATE_CONNECTION: return "SEPARATE_CONNECTION";
            case RIL_REQUEST_SET_MUTE: return "SET_MUTE";
            case RIL_REQUEST_GET_MUTE: return "GET_MUTE";
            case RIL_REQUEST_QUERY_CLIP: return "QUERY_CLIP";
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: return "LAST_DATA_CALL_FAIL_CAUSE";
            case RIL_REQUEST_DATA_CALL_LIST: return "DATA_CALL_LIST";
            case RIL_REQUEST_RESET_RADIO: return "RESET_RADIO";
            case RIL_REQUEST_OEM_HOOK_RAW: return "OEM_HOOK_RAW";
            case RIL_REQUEST_OEM_HOOK_STRINGS: return "OEM_HOOK_STRINGS";
            case RIL_REQUEST_SCREEN_STATE: return "SCREEN_STATE";
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: return "SET_SUPP_SVC_NOTIFICATION";
            case RIL_REQUEST_WRITE_SMS_TO_SIM: return "WRITE_SMS_TO_SIM";
            case RIL_REQUEST_DELETE_SMS_ON_SIM: return "DELETE_SMS_ON_SIM";
            case RIL_REQUEST_SET_BAND_MODE: return "SET_BAND_MODE";
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: return "QUERY_AVAILABLE_BAND_MODE";
            case RIL_REQUEST_STK_GET_PROFILE: return "REQUEST_STK_GET_PROFILE";
            case RIL_REQUEST_STK_SET_PROFILE: return "REQUEST_STK_SET_PROFILE";
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: return "REQUEST_STK_SEND_ENVELOPE_COMMAND";
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: return "REQUEST_STK_SEND_TERMINAL_RESPONSE";
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: return "REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM";
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: return "REQUEST_EXPLICIT_CALL_TRANSFER";
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: return "REQUEST_SET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: return "REQUEST_GET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: return "REQUEST_GET_NEIGHBORING_CELL_IDS";
            case RIL_REQUEST_SET_LOCATION_UPDATES: return "REQUEST_SET_LOCATION_UPDATES";
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: return "RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: return "RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE";
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: return "RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE";
            case RIL_REQUEST_SET_TTY_MODE: return "RIL_REQUEST_SET_TTY_MODE";
            case RIL_REQUEST_QUERY_TTY_MODE: return "RIL_REQUEST_QUERY_TTY_MODE";
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: return "RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: return "RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_FLASH: return "RIL_REQUEST_CDMA_FLASH";
            case RIL_REQUEST_CDMA_BURST_DTMF: return "RIL_REQUEST_CDMA_BURST_DTMF";
            case RIL_REQUEST_CDMA_SEND_SMS: return "RIL_REQUEST_CDMA_SEND_SMS";
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: return "RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: return "RIL_REQUEST_GSM_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: return "RIL_REQUEST_GSM_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: return "RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: return "RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: return "RIL_REQUEST_GSM_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: return "RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY";
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: return "RIL_REQUEST_CDMA_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_SUBSCRIPTION: return "RIL_REQUEST_CDMA_SUBSCRIPTION";
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: return "RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM";
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: return "RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM";
            case RIL_REQUEST_DEVICE_IDENTITY: return "RIL_REQUEST_DEVICE_IDENTITY";
            case RIL_REQUEST_GET_SMSC_ADDRESS: return "RIL_REQUEST_GET_SMSC_ADDRESS";
            case RIL_REQUEST_SET_SMSC_ADDRESS: return "RIL_REQUEST_SET_SMSC_ADDRESS";
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: return "REQUEST_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: return "RIL_REQUEST_REPORT_SMS_MEMORY_STATUS";
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: return "RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING";
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE: return "RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_ISIM_AUTHENTICATION: return "RIL_REQUEST_ISIM_AUTHENTICATION";
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU: return "RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU";
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS: return "RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS";
            case RIL_REQUEST_VOICE_RADIO_TECH: return "RIL_REQUEST_VOICE_RADIO_TECH";
            case RIL_REQUEST_GET_CELL_INFO_LIST: return "RIL_REQUEST_GET_CELL_INFO_LIST";
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE: return "RIL_REQUEST_SET_CELL_INFO_LIST_RATE";
            case RIL_REQUEST_SET_INITIAL_ATTACH_APN: return "RIL_REQUEST_SET_INITIAL_ATTACH_APN";
            case RIL_REQUEST_SET_DATA_PROFILE: return "RIL_REQUEST_SET_DATA_PROFILE";
            case RIL_REQUEST_IMS_REGISTRATION_STATE: return "RIL_REQUEST_IMS_REGISTRATION_STATE";
            case RIL_REQUEST_IMS_SEND_SMS: return "RIL_REQUEST_IMS_SEND_SMS";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC: return "RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC";
            case RIL_REQUEST_SIM_OPEN_CHANNEL: return "RIL_REQUEST_SIM_OPEN_CHANNEL";
            case RIL_REQUEST_SIM_CLOSE_CHANNEL: return "RIL_REQUEST_SIM_CLOSE_CHANNEL";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL: return "RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL";
            case RIL_REQUEST_NV_READ_ITEM: return "RIL_REQUEST_NV_READ_ITEM";
            case RIL_REQUEST_NV_WRITE_ITEM: return "RIL_REQUEST_NV_WRITE_ITEM";
            case RIL_REQUEST_NV_WRITE_CDMA_PRL: return "RIL_REQUEST_NV_WRITE_CDMA_PRL";
            case RIL_REQUEST_NV_RESET_CONFIG: return "RIL_REQUEST_NV_RESET_CONFIG";
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION: return "RIL_REQUEST_SET_UICC_SUBSCRIPTION";
            case RIL_REQUEST_ALLOW_DATA: return "RIL_REQUEST_ALLOW_DATA";
            case RIL_REQUEST_GET_HARDWARE_CONFIG: return "GET_HARDWARE_CONFIG";
            case RIL_REQUEST_SIM_AUTHENTICATION: return "RIL_REQUEST_SIM_AUTHENTICATION";
            case RIL_REQUEST_SHUTDOWN: return "RIL_REQUEST_SHUTDOWN";
            case RIL_REQUEST_SET_RADIO_CAPABILITY:
                    return "RIL_REQUEST_SET_RADIO_CAPABILITY";
            case RIL_REQUEST_GET_RADIO_CAPABILITY:
                    return "RIL_REQUEST_GET_RADIO_CAPABILITY";
            /// M: CC010: Add RIL interface @{
            case RIL_REQUEST_HANGUP_ALL: return "HANGUP_ALL";
            case RIL_REQUEST_FORCE_RELEASE_CALL: return "FORCE_RELEASE_CALL";
            case RIL_REQUEST_SET_CALL_INDICATION: return "SET_CALL_INDICATION";
            case RIL_REQUEST_EMERGENCY_DIAL: return "EMERGENCY_DIAL";
            case RIL_REQUEST_SET_ECC_SERVICE_CATEGORY: return "SET_ECC_SERVICE_CATEGORY";
            case RIL_REQUEST_SET_ECC_LIST: return "SET_ECC_LIST";
            /// @}
            /// M: CC077: 2/3G CAPABILITY_HIGH_DEF_AUDIO @{
            case RIL_REQUEST_SET_SPEECH_CODEC_INFO: return "SET_SPEECH_CODEC_INFO";
            /// @}
            /// M: For 3G VT only @{
            case RIL_REQUEST_VT_DIAL: return "RIL_REQUEST_VT_DIAL";
            case RIL_REQUEST_VOICE_ACCEPT: return "VOICE_ACCEPT";
            case RIL_REQUEST_REPLACE_VT_CALL: return "RIL_REQUEST_REPLACE_VT_CALL";
            /// @}

            /// M: IMS feature. @{
            case RIL_REQUEST_ADD_IMS_CONFERENCE_CALL_MEMBER: return "RIL_REQUEST_ADD_IMS_CONFERENCE_CALL_MEMBER";
            case RIL_REQUEST_REMOVE_IMS_CONFERENCE_CALL_MEMBER: return "RIL_REQUEST_REMOVE_IMS_CONFERENCE_CALL_MEMBER";
            case RIL_REQUEST_DIAL_WITH_SIP_URI: return "RIL_REQUEST_DIAL_WITH_SIP_URI";
            case RIL_REQUEST_RESUME_CALL: return "RIL_REQUEST_RESUNME_CALL";
            case RIL_REQUEST_HOLD_CALL: return "RIL_REQUEST_HOLD_CALL";
            /// @}

            //MTK-START SS
            case RIL_REQUEST_GET_COLP: return "GET_COLP";
            case RIL_REQUEST_SET_COLP: return "SET_COLP";
            case RIL_REQUEST_GET_COLR: return "GET_COLR";
            //MTK-END SS

            //MTK-START SIM ME lock
            case RIL_REQUEST_QUERY_SIM_NETWORK_LOCK: return "QUERY_SIM_NETWORK_LOCK";
            case RIL_REQUEST_SET_SIM_NETWORK_LOCK: return "SET_SIM_NETWORK_LOCK";
            //MTK-END SIM ME lock
            //ISIM
            case RIL_REQUEST_GENERAL_SIM_AUTH: return "RIL_REQUEST_GENERAL_SIM_AUTH";
            case RIL_REQUEST_OPEN_ICC_APPLICATION: return "RIL_REQUEST_OPEN_ICC_APPLICATION";
            case RIL_REQUEST_GET_ICC_APPLICATION_STATUS: return "RIL_REQUEST_GET_ICC_APPLICATION_STATUS";
            case RIL_REQUEST_SIM_IO_EX: return "SIM_IO_EX";

            // PHB Start
            case RIL_REQUEST_QUERY_PHB_STORAGE_INFO: return "RIL_REQUEST_QUERY_PHB_STORAGE_INFO";
            case RIL_REQUEST_WRITE_PHB_ENTRY: return "RIL_REQUEST_WRITE_PHB_ENTRY";
            case RIL_REQUEST_READ_PHB_ENTRY: return "RIL_REQUEST_READ_PHB_ENTRY";
            case RIL_REQUEST_QUERY_UPB_CAPABILITY: return "RIL_REQUEST_QUERY_UPB_CAPABILITY";
            case RIL_REQUEST_EDIT_UPB_ENTRY: return "RIL_REQUEST_EDIT_UPB_ENTRY";
            case RIL_REQUEST_DELETE_UPB_ENTRY: return "RIL_REQUEST_DELETE_UPB_ENTRY";
            case RIL_REQUEST_READ_UPB_GAS_LIST: return "RIL_REQUEST_READ_UPB_GAS_LIST";
            case RIL_REQUEST_READ_UPB_GRP: return "RIL_REQUEST_READ_UPB_GRP";
            case RIL_REQUEST_WRITE_UPB_GRP: return "RIL_REQUEST_WRITE_UPB_GRP";
            case RIL_REQUEST_GET_PHB_STRING_LENGTH: return "RIL_REQUEST_GET_PHB_STRING_LENGTH";
            case RIL_REQUEST_GET_PHB_MEM_STORAGE: return "RIL_REQUEST_GET_PHB_MEM_STORAGE";
            case RIL_REQUEST_SET_PHB_MEM_STORAGE: return "RIL_REQUEST_SET_PHB_MEM_STORAGE";
            case RIL_REQUEST_READ_PHB_ENTRY_EXT: return "RIL_REQUEST_READ_PHB_ENTRY_EXT";
            case RIL_REQUEST_WRITE_PHB_ENTRY_EXT: return "RIL_REQUEST_WRITE_PHB_ENTRY_EXT";
            // PHB End

            /* M: network part start */
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT: return "SET_NETWORK_SELECTION_MANUAL_WITH_ACT";
            case RIL_REQUEST_GET_POL_CAPABILITY: return "RIL_REQUEST_GET_POL_CAPABILITY";
            case RIL_REQUEST_GET_POL_LIST: return "RIL_REQUEST_GET_POL_LIST";
            case RIL_REQUEST_SET_POL_ENTRY: return "RIL_REQUEST_SET_POL_ENTRY";
            case RIL_REQUEST_SET_TRM: return "RIL_REQUEST_SET_TRM";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT : return "QUERY_AVAILABLE_NETWORKS_WITH_ACT";
            //Femtocell (CSG) feature START
            case RIL_REQUEST_GET_FEMTOCELL_LIST: return "RIL_REQUEST_GET_FEMTOCELL_LIST";
            case RIL_REQUEST_ABORT_FEMTOCELL_LIST: return "RIL_REQUEST_ABORT_FEMTOCELL_LIST";
            case RIL_REQUEST_SELECT_FEMTOCELL: return "RIL_REQUEST_SELECT_FEMTOCELL";
            //Femtocell (CSG) feature END
            /* M: network part end */
            case RIL_REQUEST_STK_EVDL_CALL_BY_AP: return "RIL_REQUEST_STK_EVDL_CALL_BY_AP";
            case RIL_REQUEST_QUERY_MODEM_TYPE: return "RIL_REQUEST_QUERY_MODEM_TYPE";
            case RIL_REQUEST_STORE_MODEM_TYPE: return "RIL_REQUEST_STORE_MODEM_TYPE";
            case RIL_REQUEST_SIM_GET_ATR: return "SIM_GET_ATR";
            case RIL_REQUEST_SIM_OPEN_CHANNEL_WITH_SW: return "SIM_OPEN_CHANNEL_WITH_SW";
            //VoLTE
            case RIL_REQUEST_SETUP_DEDICATE_DATA_CALL: return "RIL_REQUEST_SETUP_DEDICATE_DATA_CALL";
            case RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL: return "RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL";
            case RIL_REQUEST_MODIFY_DATA_CALL: return "RIL_REQUEST_MODIFY_DATA_CALL";
            case RIL_REQUEST_ABORT_SETUP_DATA_CALL: return "RIL_REQUEST_ABORT_SETUP_DATA_CALL";
            case RIL_REQUEST_PCSCF_DISCOVERY_PCO: return "RIL_REQUEST_PCSCF_DISCOVERY_PCO";
            case RIL_REQUEST_CLEAR_DATA_BEARER: return "RIL_REQUEST_CLEAR_DATA_BEARER";

            // IMS
            case RIL_REQUEST_SET_IMS_ENABLE: return "RIL_REQUEST_SET_IMS_ENABLE";

            // M: Fast Dormancy
            case RIL_REQUEST_SET_SCRI: return "RIL_REQUEST_SET_SCRI";
            case RIL_REQUEST_SET_FD_MODE: return "RIL_REQUEST_SET_FD_MODE";
            // MTK-START, SMS part
            case RIL_REQUEST_GET_SMS_PARAMS: return "RIL_REQUEST_GET_SMS_PARAMS";
            case RIL_REQUEST_SET_SMS_PARAMS: return "RIL_REQUEST_SET_SMS_PARAMS";
            case RIL_REQUEST_GET_SMS_SIM_MEM_STATUS: return "RIL_REQUEST_GET_SMS_SIM_MEM_STATUS";
            case RIL_REQUEST_SET_ETWS: return "RIL_REQUEST_SET_ETWS";
            case RIL_REQUEST_SET_CB_CHANNEL_CONFIG_INFO:
                return "RIL_REQUEST_SET_CB_CHANNEL_CONFIG_INFO";
            case RIL_REQUEST_SET_CB_LANGUAGE_CONFIG_INFO:
                return "RIL_REQUEST_SET_CB_LANGUAGE_CONFIG_INFO";
            case RIL_REQUEST_GET_CB_CONFIG_INFO: return "RIL_REQUEST_GET_CB_CONFIG_INFO";
            case RIL_REQUEST_REMOVE_CB_MESSAGE: return "RIL_REQUEST_REMOVE_CB_MESSAGE";
            // MTK-END, SMS part
            case RIL_REQUEST_SET_DATA_CENTRIC: return "RIL_REQUEST_SET_DATA_CENTRIC";

            case RIL_REQUEST_MODEM_POWEROFF: return "MODEM_POWEROFF";
            case RIL_REQUEST_MODEM_POWERON: return "MODEM_POWERON";
            // M: CC33 LTE.
            case RIL_REQUEST_SET_DATA_ON_TO_MD: return "RIL_REQUEST_SET_DATA_ON_TO_MD";
            case RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE: return "RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE";
            case RIL_REQUEST_BTSIM_CONNECT: return "RIL_REQUEST_BTSIM_CONNECT";
            case RIL_REQUEST_BTSIM_DISCONNECT_OR_POWEROFF: return "RIL_REQUEST_BTSIM_DISCONNECT_OR_POWEROFF";
            case RIL_REQUEST_BTSIM_POWERON_OR_RESETSIM: return "RIL_REQUEST_BTSIM_POWERON_OR_RESETSIM";
            case RIL_REQUEST_BTSIM_TRANSFERAPDU: return "RIL_REQUEST_SEND_BTSIM_TRANSFERAPDU";

            /// M: IMS VoLTE conference dial feature. @{
            case RIL_REQUEST_CONFERENCE_DIAL: return "RIL_REQUEST_CONFERENCE_DIAL";
            /// @}
            case RIL_REQUEST_RELOAD_MODEM_TYPE: return "RIL_REQUEST_RELOAD_MODEM_TYPE";
            /// M: CC010: Add RIL interface @{
            case RIL_REQUEST_SET_IMS_CALL_STATUS: return "RIL_REQUEST_SET_IMS_CALL_STATUS";
            /// @}

            /// M: CC072: Add Customer proprietary-IMS RIL interface. @{
            case RIL_REQUEST_SET_SRVCC_CALL_CONTEXT_TRANSFER: return "RIL_REQUEST_SET_SRVCC_CALL_CONTEXT_TRANSFER";
            case RIL_REQUEST_UPDATE_IMS_REGISTRATION_STATUS: return "RIL_REQUEST_UPDATE_IMS_REGISTRATION_STATUS";
            /// @}

            /// M: SVLTE remote SIM access feature
            case RIL_REQUEST_CONFIG_MODEM_STATUS: return "RIL_REQUEST_CONFIG_MODEM_STATUS";
            /* M: C2K part start */
            case RIL_REQUEST_GET_NITZ_TIME: return "RIL_REQUEST_GET_NITZ_TIME";
            case RIL_REQUEST_QUERY_UIM_INSERTED: return "RIL_REQUEST_QUERY_UIM_INSERTED";
            case RIL_REQUEST_SWITCH_HPF: return "RIL_REQUEST_SWITCH_HPF";
            case RIL_REQUEST_SET_AVOID_SYS: return "RIL_REQUEST_SET_AVOID_SYS";
            case RIL_REQUEST_QUERY_AVOID_SYS: return "RIL_REQUEST_QUERY_AVOID_SYS";
            case RIL_REQUEST_QUERY_CDMA_NETWORK_INFO: return "RIL_REQUEST_QUERY_CDMA_NETWORK_INFO";
            case RIL_REQUEST_GET_LOCAL_INFO: return "RIL_REQUEST_GET_LOCAL_INFO";
            case RIL_REQUEST_UTK_REFRESH: return "RIL_REQUEST_UTK_REFRESH";
            case RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS:
                return "RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS";
            case RIL_REQUEST_QUERY_NETWORK_REGISTRATION:
                return "RIL_REQUEST_QUERY_NETWORK_REGISTRATION";
            case RIL_REQUEST_AGPS_TCP_CONNIND: return "RIL_REQUEST_AGPS_TCP_CONNIND";
            case RIL_REQUEST_AGPS_SET_MPC_IPPORT: return "RIL_REQUEST_AGPS_SET_MPC_IPPORT";
            case RIL_REQUEST_AGPS_GET_MPC_IPPORT: return "RIL_REQUEST_AGPS_GET_MPC_IPPORT";
            case RIL_REQUEST_SET_MEID: return "RIL_REQUEST_SET_MEID";
            case RIL_REQUEST_SET_ETS_DEV: return "RIL_REQUEST_SET_ETS_DEV";
            case RIL_REQUEST_WRITE_MDN: return "RIL_REQUEST_WRITE_MDN";
            case RIL_REQUEST_SET_VIA_TRM: return "RIL_REQUEST_SET_VIA_TRM";
            case RIL_REQUEST_SET_ARSI_THRESHOLD: return "RIL_REQUEST_SET_ARSI_THRESHOLD";
            case RIL_REQUEST_QUERY_UTK_MENU_FROM_MD: return "RIL_REQUEST_QUERY_UTK_MENU_FROM_MD";
            case RIL_REQUEST_QUERY_STK_MENU_FROM_MD: return "RIL_REQUEST_QUERY_STK_MENU_FROM_MD";
            /* M: C2K part end */
            // M: [C2K][MD IRAT]RIL
            case RIL_REQUEST_SET_ACTIVE_PS_SLOT: return "RIL_REQUEST_SET_ACTIVE_PS_SLOT";
            case RIL_REQUEST_CONFIRM_INTER_3GPP_IRAT_CHANGE:
                return "RIL_REQUEST_CONFIRM_INTER_3GPP_IRAT_CHANGE";
            case RIL_REQUEST_DEACTIVATE_LINK_DOWN_PDN:
                return "RIL_REQUEST_DEACTIVATE_LINK_DOWN_PDN";
            /// @}
            // M: [C2K][AP IRAT] start
            case RIL_REQUEST_TRIGGER_LTE_BG_SEARCH: return "RIL_REQUEST_TRIGGER_LTE_BG_SEARCH";
            case RIL_REQUEST_SET_LTE_EARFCN_ENABLED: return "RIL_REQUEST_SET_LTE_EARFCN_ENABLED";

            /// M: [C2K][SVLTE] Set the SVLTE RAT mode. @{
            case RIL_REQUEST_SET_SVLTE_RAT_MODE: return "RIL_REQUEST_SET_SVLTE_RAT_MODE";
            /// M: [C2K][SVLTE] Set the SVLTE RAT mode. @}

            /// M: [C2K][IR] Support SVLTE IR feature. @{
            case RIL_REQUEST_SET_REG_SUSPEND_ENABLED: return "RIL_REQUEST_SET_REG_SUSPEND_ENABLED";
            case RIL_REQUEST_RESUME_REGISTRATION: return "RIL_REQUEST_RESUME_REGISTRATION";
            case RIL_REQUEST_SET_REG_SUSPEND_ENABLED_CDMA:
                return "RIL_REQUEST_SET_REG_SUSPEND_ENABLED_CDMA";
            case RIL_REQUEST_RESUME_REGISTRATION_CDMA:
                return "RIL_REQUEST_RESUME_REGISTRATION_CDMA";
            case RIL_REQUEST_CONFIG_IRAT_MODE:
                return "RIL_REQUEST_CONFIG_IRAT_MODE";
            case RIL_REQUEST_CONFIG_EVDO_MODE:
                return "RIL_REQUEST_CONFIG_EVDO_MODE";
            /// M: [C2K][IR] Support SVLTE IR feature. @}

            case RIL_REQUEST_SET_STK_UTK_MODE:
                return "RIL_REQUEST_SET_STK_UTK_MODE";

            // M: Notify RILJ that call fade happened
            case RIL_UNSOL_CDMA_SIGNAL_FADE:
                return "RIL_UNSOL_CDMA_SIGNAL_FADE";
            // M: Notify RILJ that the AT+EFNM was received
            case RIL_UNSOL_CDMA_TONE_SIGNALS:
                return "RIL_UNSOL_CDMA_TONE_SIGNALS";

            case RIL_REQUEST_SWITCH_ANTENNA: return "RIL_REQUEST_SWITCH_ANTENNA";
            default: return "<unknown request> (" + request + ")";
        }
    }

    // MTK doesn't have the type parameter
    // just ignore that
    @Override
    public void
    supplyDepersonalization(String netpin, String type, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_DEPERSONALIZATION_CODE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) +
                        " Type:" + type);
        riljLog("supplyDepersonalization: type ignored on MTK!");

        rr.mParcel.writeInt(1);
        rr.mParcel.writeString(type);
        // rr.mParcel.writeString(netpin);

        send(rr);
    }

    // VoLTE
    private Object
    responseDeactivateDataCall(Parcel p) {
        int [] cidArray = null;
        if (p.dataSize() > 0 ) {    //checking Parcel data value
            cidArray = (int []) responseInts(p);
        }

        return cidArray;
    }

    private Object
    responseOperator(Parcel p) {
        int num;
        String response[] = null;

        response = p.readStringArray();

        if (false) {
            num = p.readInt();

            response = new String[num];
            for (int i = 0; i < num; i++) {
                response[i] = p.readString();
            }
        }

        for (int i = 0; i < response.length; i++) {
            if((response[i] != null) && (response[i].startsWith("uCs2") == true))
            {
                riljLog("responseOperator handling UCS2 format name: response[" + i + "]");
                try{
                    response[i] = new String(hexStringToBytes(response[i].substring(4)),"UTF-16");
                }catch(UnsupportedEncodingException ex){
                    riljLog("responseOperatorInfos UnsupportedEncodingException");
                }
            }
        }

        // NOTE: the original code seemingly has some nontrivial SpnOverride
        // modifications, so I'm not going to port that.
        if (response.length > 2 && response[2] != null) {
            if (response[0] != null && (response[0].equals("") || response[0].equals(response[2]))) {
	        Operators init = new Operators ();
	        String temp = init.unOptimizedOperatorReplace(response[2]);
	        riljLog("lookup RIL responseOperator() " + response[2] + " gave " + temp + " was " + response[0] + "/" + response[1] + " before.");
	        response[0] = temp;
	        response[1] = temp;
            }
        }

        return response;
    }

    private Object
    responsePhoneId() {
        return mInstanceId;
    }

    static String
    responseToString(int request)
    {
/*
 cat libs/telephony/ril_unsol_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch(request) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: return "UNSOL_RESPONSE_RADIO_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: return "UNSOL_RESPONSE_CALL_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED: return "UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NEW_SMS: return "UNSOL_RESPONSE_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: return "UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT";
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: return "UNSOL_RESPONSE_NEW_SMS_ON_SIM";
            case RIL_UNSOL_ON_USSD: return "UNSOL_ON_USSD";
            case RIL_UNSOL_ON_USSD_REQUEST: return "UNSOL_ON_USSD_REQUEST";
            case RIL_UNSOL_NITZ_TIME_RECEIVED: return "UNSOL_NITZ_TIME_RECEIVED";
            case RIL_UNSOL_SIGNAL_STRENGTH: return "UNSOL_SIGNAL_STRENGTH";
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: return "UNSOL_DATA_CALL_LIST_CHANGED";
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: return "UNSOL_SUPP_SVC_NOTIFICATION";
            case RIL_UNSOL_STK_SESSION_END: return "UNSOL_STK_SESSION_END";
            case RIL_UNSOL_STK_PROACTIVE_COMMAND: return "UNSOL_STK_PROACTIVE_COMMAND";
            case RIL_UNSOL_STK_EVENT_NOTIFY: return "UNSOL_STK_EVENT_NOTIFY";
            case RIL_UNSOL_STK_CALL_SETUP: return "UNSOL_STK_CALL_SETUP";
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL: return "UNSOL_SIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_SIM_REFRESH: return "UNSOL_SIM_REFRESH";
            case RIL_UNSOL_CALL_RING: return "UNSOL_CALL_RING";
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED: return "UNSOL_RESPONSE_SIM_STATUS_CHANGED";
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS: return "UNSOL_RESPONSE_CDMA_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS: return "UNSOL_RESPONSE_NEW_BROADCAST_SMS";
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL: return "UNSOL_CDMA_RUIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED: return "UNSOL_RESTRICTED_STATE_CHANGED";
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE: return "UNSOL_ENTER_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_CDMA_CALL_WAITING: return "UNSOL_CDMA_CALL_WAITING";
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: return "UNSOL_CDMA_OTA_PROVISION_STATUS";
            case RIL_UNSOL_CDMA_INFO_REC: return "UNSOL_CDMA_INFO_REC";
            case RIL_UNSOL_OEM_HOOK_RAW: return "UNSOL_OEM_HOOK_RAW";
            case RIL_UNSOL_RINGBACK_TONE: return "UNSOL_RINGBACK_TONE";
            case RIL_UNSOL_RESEND_INCALL_MUTE: return "UNSOL_RESEND_INCALL_MUTE";
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED: return "CDMA_SUBSCRIPTION_SOURCE_CHANGED";
            case RIL_UNSOl_CDMA_PRL_CHANGED: return "UNSOL_CDMA_PRL_CHANGED";
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE: return "UNSOL_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_RIL_CONNECTED: return "UNSOL_RIL_CONNECTED";
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED: return "UNSOL_VOICE_RADIO_TECH_CHANGED";
            case RIL_UNSOL_CELL_INFO_LIST: return "UNSOL_CELL_INFO_LIST";
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                return "UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED:
                    return "RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED";
            case RIL_UNSOL_SRVCC_STATE_NOTIFY:
                    return "UNSOL_SRVCC_STATE_NOTIFY";
            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED: return "RIL_UNSOL_HARDWARE_CONFIG_CHANGED";
            case RIL_UNSOL_RADIO_CAPABILITY:
                    return "RIL_UNSOL_RADIO_CAPABILITY";
            case RIL_UNSOL_ON_SS: return "UNSOL_ON_SS";
            case RIL_UNSOL_STK_CC_ALPHA_NOTIFY: return "UNSOL_STK_CC_ALPHA_NOTIFY";
            /// M: CC010: Add RIL interface @{
            case RIL_UNSOL_CALL_FORWARDING: return "UNSOL_CALL_FORWARDING";
            case RIL_UNSOL_CRSS_NOTIFICATION: return "UNSOL_CRSS_NOTIFICATION";
            case RIL_UNSOL_INCOMING_CALL_INDICATION: return "UNSOL_INCOMING_CALL_INDICATION";
            case RIL_UNSOL_CIPHER_INDICATION: return "UNSOL_CIPHER_INDICATION";
            case RIL_UNSOL_CNAP: return "UNSOL_CNAP";
            /// @}
            /// M: CC077: 2/3G CAPABILITY_HIGH_DEF_AUDIO @{
            case RIL_UNSOL_SPEECH_CODEC_INFO: return "UNSOL_SPEECH_CODEC_INFO";
            /// @}
            //MTK-START multiple application support
            case RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED: return "RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED";
            //MTK-END multiple application support
            case RIL_UNSOL_SIM_MISSING: return "UNSOL_SIM_MISSING";
            case RIL_UNSOL_VIRTUAL_SIM_ON: return "UNSOL_VIRTUAL_SIM_ON";
            case RIL_UNSOL_VIRTUAL_SIM_OFF: return "UNSOL_VIRTUAL_SIM_ON_OFF";
            case RIL_UNSOL_SIM_RECOVERY: return "UNSOL_SIM_RECOVERY";
            case RIL_UNSOL_SIM_PLUG_OUT: return "UNSOL_SIM_PLUG_OUT";
            case RIL_UNSOL_SIM_PLUG_IN: return "UNSOL_SIM_PLUG_IN";
            case RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED: return "RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED";
            case RIL_UNSOL_DATA_ALLOWED: return "RIL_UNSOL_DATA_ALLOWED";
            case RIL_UNSOL_PHB_READY_NOTIFICATION: return "UNSOL_PHB_READY_NOTIFICATION";
            case RIL_UNSOL_IMEI_LOCK: return "UNSOL_IMEI_LOCK";
            case RIL_UNSOL_RESPONSE_ACMT: return "UNSOL_ACMT_INFO";
            case RIL_UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED: return "UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_MMRR_STATUS_CHANGED: return "UNSOL_RESPONSE_MMRR_STATUS_CHANGED";
            case RIL_UNSOL_NEIGHBORING_CELL_INFO: return "UNSOL_NEIGHBORING_CELL_INFO";
            case RIL_UNSOL_NETWORK_INFO: return "UNSOL_NETWORK_INFO";
            case RIL_UNSOL_INVALID_SIM: return "RIL_UNSOL_INVALID_SIM";
            case RIL_UNSOL_IMS_ENABLE_DONE: return "RIL_UNSOL_IMS_ENABLE_DONE";
            case RIL_UNSOL_IMS_DISABLE_DONE: return "RIL_UNSOL_IMS_DISABLE_DONE";
            case RIL_UNSOL_IMS_REGISTRATION_INFO: return "RIL_UNSOL_IMS_REGISTRATION_INFO";
            case RIL_UNSOL_STK_SETUP_MENU_RESET: return "RIL_UNSOL_STK_SETUP_MENU_RESET";
            case RIL_UNSOL_RESPONSE_PLMN_CHANGED: return "RIL_UNSOL_RESPONSE_PLMN_CHANGED";
            case RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED: return "RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED";
            //VoLTE
            case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED: return "RIL_UNSOL_DEDICATE_BEARER_ACTIVATED";
            case RIL_UNSOL_DEDICATE_BEARER_MODIFIED: return "RIL_UNSOL_DEDICATE_BEARER_MODIFIED";
            //Remote SIM ME lock related APIs [Start]
            case RIL_UNSOL_MELOCK_NOTIFICATION: return "RIL_UNSOL_MELOCK_NOTIFICATION";
            //Remote SIM ME lock related APIs [End]
            // M: Fast Dormancy
            case RIL_UNSOL_SCRI_RESULT: return "RIL_UNSOL_SCRI_RESULT";
            case RIL_UNSOL_STK_EVDL_CALL: return "RIL_UNSOL_STK_EVDL_CALL";
            case RIL_UNSOL_STK_CALL_CTRL: return "RIL_UNSOL_STK_CALL_CTRL";

            /// M: IMS feature. @{
            case RIL_UNSOL_ECONF_SRVCC_INDICATION: return "RIL_UNSOL_ECONF_SRVCC_INDICATION";
            //For updating conference call merged/added result.
            case RIL_UNSOL_ECONF_RESULT_INDICATION: return "RIL_UNSOL_ECONF_RESULT_INDICATION";
            //For updating call mode and pau information.
            case RIL_UNSOL_CALL_INFO_INDICATION : return "RIL_UNSOL_CALL_INFO_INDICATION";
            /// @}

            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO: return "RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO";
            case RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION: return "RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION";
            // M: CC33 LTE.
            case RIL_UNSOL_RAC_UPDATE: return "RIL_UNSOL_RAC_UPDATE";
            case RIL_UNSOL_REMOVE_RESTRICT_EUTRAN: return "RIL_UNSOL_REMOVE_RESTRICT_EUTRAN";

            //MTK-START for MD state change
            case RIL_UNSOL_MD_STATE_CHANGE: return "RIL_UNSOL_MD_STATE_CHANGE";
            //MTK-END for MD state change

            case RIL_UNSOL_MO_DATA_BARRING_INFO: return "RIL_UNSOL_MO_DATA_BARRING_INFO";
            case RIL_UNSOL_SSAC_BARRING_INFO: return "RIL_UNSOL_SSAC_BARRING_INFO";

            /// M: CC071: Add Customer proprietary-IMS RIL interface. @{
            case RIL_UNSOL_EMERGENCY_BEARER_SUPPORT_NOTIFY: return "RIL_UNSOL_EMERGENCY_BEARER_SUPPORT_NOTIFY";
            /// @}

            /* M: C2K part start */
            case RIL_UNSOL_CDMA_CALL_ACCEPTED: return "RIL_UNSOL_CDMA_CALL_ACCEPTED";
            case RIL_UNSOL_UTK_SESSION_END: return "RIL_UNSOL_UTK_SESSION_END";
            case RIL_UNSOL_UTK_PROACTIVE_COMMAND: return "RIL_UNSOL_UTK_PROACTIVE_COMMAND";
            case RIL_UNSOL_UTK_EVENT_NOTIFY: return "RIL_UNSOL_UTK_EVENT_NOTIFY";
            case RIL_UNSOL_VIA_GPS_EVENT: return "RIL_UNSOL_VIA_GPS_EVENT";
            case RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE: return "RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE";
            case RIL_UNSOL_VIA_INVALID_SIM_DETECTED: return "RIL_UNSOL_VIA_INVALID_SIM_DETECTED";
            /// M: [C2K][IR] Support SVLTE IR feature. @{
            case RIL_UNSOL_CDMA_PLMN_CHANGED: return "RIL_UNSOL_CDMA_PLMN_CHANGED";
            /// M: [C2K][IR] Support SVLTE IR feature. @}
            /// M: [C2K][IR][MD-IRAT] URC for GMSS RAT changed. @{
            case RIL_UNSOL_GMSS_RAT_CHANGED: return "RIL_UNSOL_GMSS_RAT_CHANGED";
            /// M: [C2K][IR][MD-IRAT] URC for GMSS RAT changed. @}
            /// M: [C2K] for ps type changed.
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_TYPE_CHANGED:
                return "RIL_UNSOL_RESPONSE_DATA_NETWORK_TYPE_CHANGED";
            /* M: C2K part end */
            case RIL_UNSOL_ABNORMAL_EVENT: return "RIL_UNSOL_ABNORMAL_EVENT";
            case RIL_UNSOL_CDMA_CARD_TYPE: return "RIL_UNSOL_CDMA_CARD_TYPE";
            /// M: [C2K][MD IRAT] start
            case RIL_UNSOL_INTER_3GPP_IRAT_STATE_CHANGE:
                return "UNSOL_INTER_3GPP_IRAT_STATE_CHANGE";
            /// @} [C2K][MD IRAT] end
            /// M:[C2K] for eng mode
            case RIL_UNSOL_ENG_MODE_NETWORK_INFO: return "RIL_UNSOL_ENG_MODE_NETWORK_INFO";
            // M: [C2K][AP IRAT]
            case RIL_UNSOL_LTE_BG_SEARCH_STATUS: return "RIL_UNSOL_LTE_BG_SEARCH_STATUS";
            case RIL_UNSOL_LTE_EARFCN_INFO: return "RIL_UNSOL_LTE_EARFCN_INFO";

            // MTK-START, SMS part
            // SMS ready notification
            case RIL_UNSOL_SMS_READY_NOTIFICATION: return "RIL_UNSOL_SMS_READY_NOTIFICATION";
            // New sms but phone storage is full
            case RIL_UNSOL_ME_SMS_STORAGE_FULL: return "RIL_UNSOL_ME_SMS_STORAGE_FULL";
            // ETWS primary notification
            case RIL_UNSOL_RESPONSE_ETWS_NOTIFICATION: return "RIL_UNSOL_RESPONSE_ETWS_NOTIFICATION";
            // MTK-END, SMS part
            case RIL_UNSOL_CDMA_IMSI_READY: return "RIL_UNSOL_CDMA_IMSI_READY";
            case RIL_UNSOL_IMSI_REFRESH_DONE: return "RIL_UNSOL_IMSI_REFRESH_DONE";
            // M: Notify RILJ that the AT+EUSIM was received
            case RIL_UNSOL_EUSIM_READY: return "UNSOL_EUSIM_READY";
            /// M: For 3G VT only @{
            case RIL_UNSOL_VT_STATUS_INFO: return "UNSOL_VT_STATUS_INFO";
            case RIL_UNSOL_VT_RING_INFO: return "UNSOL_VT_RING_INFO";
            /// @}
            default: return "<unknown response> (" + request + ")";
        }
    }

    /*
     * to protect modem status we need to avoid two case :
     * 1. DTMF start -> CHLD request -> DTMF stop
     * 2. CHLD request -> DTMF request
     */
    private void handleChldRelatedRequest(RILRequest rr) {
        synchronized (mDtmfReqQueue) {
            int queueSize = mDtmfReqQueue.size();
            int i, j;
            if (queueSize > 0) {
                RILRequest rr2 = mDtmfReqQueue.get();
                if (rr2.mRequest == RIL_REQUEST_DTMF_START) {
                    // need to send the STOP command
                    if (RILJ_LOGD) riljLog("DTMF queue isn't 0, first request is START, send stop dtmf and pending switch");
                    if (queueSize > 1) {
                        j = 2;
                    } else {
                        // need to create a new STOP command
                        j = 1;
                    }
                    if (RILJ_LOGD) riljLog("queue size  " + mDtmfReqQueue.size());

                    for (i = queueSize - 1; i >= j; i--) {
                        mDtmfReqQueue.remove(i);
                    }
                    if (RILJ_LOGD) riljLog("queue size  after " + mDtmfReqQueue.size());
                    if (mDtmfReqQueue.size() == 1) { // only start command, we need to add stop command
                        RILRequest rr3 = RILRequest.obtain(RIL_REQUEST_DTMF_STOP, null);
                        if (RILJ_LOGD) riljLog("add dummy stop dtmf request");
                        mDtmfReqQueue.stop();
                        mDtmfReqQueue.add(rr3);
                    }
                }
                else {
                    // first request is STOP, just remove it and send switch
                    if (RILJ_LOGD) riljLog("DTMF queue isn't 0, first request is STOP, penging switch");
                    j = 1;
                    for (i = queueSize - 1; i >= j; i--) {
                        mDtmfReqQueue.remove(i);
                    }
                }
                mDtmfReqQueue.setPendingRequest(rr);
            } else {
                if (RILJ_LOGD) riljLog("DTMF queue is 0, send switch Immediately");
                mDtmfReqQueue.setSendChldRequest();
                send(rr);
            }
        }
    }

    private
    void setCallIndication(String[] incomingCallInfo) {
	RILRequest rr
            = RILRequest.obtain(RIL_REQUEST_SET_CALL_INDICATION, null);

	int callId = Integer.parseInt(incomingCallInfo[0]);
        int callMode = Integer.parseInt(incomingCallInfo[3]);
        int seqNumber = Integer.parseInt(incomingCallInfo[4]);

	// some guess work is needed here, for now, just 0
	callMode = 0;

        rr.mParcel.writeInt(3);

        rr.mParcel.writeInt(callMode);
        rr.mParcel.writeInt(callId);
        rr.mParcel.writeInt(seqNumber);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
            + requestToString(rr.mRequest) + " " + callMode + " " + callId + " " + seqNumber);

        send(rr);
    }

    // Override setupDataCall as the MTK RIL needs 8th param CID (hardwired to 1?)
    @Override
    public void
    setupDataCall(String radioTechnology, String profile, String apn,
            String user, String password, String authType, String protocol,
            Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SETUP_DATA_CALL, result);

        rr.mParcel.writeInt(8);

        rr.mParcel.writeString(radioTechnology);
        rr.mParcel.writeString(profile);
        rr.mParcel.writeString(apn);
        rr.mParcel.writeString(user);
        rr.mParcel.writeString(password);
        rr.mParcel.writeString(authType);
        rr.mParcel.writeString(protocol);
        rr.mParcel.writeString("1");

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + " " + radioTechnology + " "
                + profile + " " + apn + " " + user + " "
                + password + " " + authType + " " + protocol + "1");

        send(rr);
    }

    protected Object
    responseSignalStrength(Parcel p) {
        SignalStrength s = SignalStrength.makeSignalStrengthFromRilParcel(p);
	return new SignalStrength(s.getGsmSignalStrength(),
				  s.getGsmBitErrorRate(),
				  s.getCdmaDbm(),
				  s.getCdmaEcio(),
				  s.getEvdoDbm(),
				  s.getEvdoEcio(),
				  s.getEvdoSnr(),
				  true);
    }

    private void setRadioStateFromRILInt (int stateCode) {
        switch (stateCode) {
	case 0: case 1: break; // radio off
	default:
	    {
	        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_GPRS_TRANSFER_TYPE, null);

		if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

		rr.mParcel.writeInt(1);
		rr.mParcel.writeInt(1);

		send(rr);
	    }
	    {
	        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_GPRS_CONNECT_TYPE, null);

		if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

		rr.mParcel.writeInt(1);
		rr.mParcel.writeInt(1);

		send(rr);
	    }
	}
    }

    public void setEccServiceCategory(int serviceCategory) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_ECC_SERVICE_CATEGORY, null);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(serviceCategory);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " " + serviceCategory);

        send(rr);
    }

    private void setEccList() {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_ECC_LIST, null);
        ArrayList<PhoneNumberUtils.EccEntry> eccList = PhoneNumberUtils.getEccList();

        rr.mParcel.writeInt(eccList.size() * 3);
        for (PhoneNumberUtils.EccEntry entry : eccList) {
            rr.mParcel.writeString(entry.getEcc());
            rr.mParcel.writeString(entry.getCategory());
            String strCondition = entry.getCondition();
            if (strCondition.equals(PhoneNumberUtils.EccEntry.ECC_FOR_MMI))
                strCondition = PhoneNumberUtils.EccEntry.ECC_NO_SIM;
            rr.mParcel.writeString(strCondition);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void setUiccSubscription(int slotId, int appIndex, int subId,
				    int subStatus, Message result) {
	    if (RILJ_LOGD) riljLog("setUiccSubscription" + slotId + " " + appIndex + " " + subId + " " + subStatus);

	    // Fake response (note: should be sent before mSubscriptionStatusRegistrants or
	    // SubscriptionManager might not set the readiness correctly)
	    AsyncResult.forMessage(result, 0, null);
	    result.sendToTarget();

	    // TODO: Actually turn off/on the radio (and don't fight with the ServiceStateTracker)
	    if (subStatus == 1 /* ACTIVATE */) {
		    // Subscription changed: enabled
		    if (mSubscriptionStatusRegistrants != null) {
			    mSubscriptionStatusRegistrants.notifyRegistrants(
									     new AsyncResult (null, new int[] {1}, null));
		    }
	    } else if (subStatus == 0 /* DEACTIVATE */) {
		    // Subscription changed: disabled
		    if (mSubscriptionStatusRegistrants != null) {
			    mSubscriptionStatusRegistrants.notifyRegistrants(
									     new AsyncResult (null, new int[] {0}, null));
		    }
	    }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setPreferredNetworkType(int networkType , Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(networkType);

        mPreviousPreferredType = mPreferredNetworkType; //ALPS00799783
        mPreferredNetworkType = networkType;

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + networkType);
        // xen0n: debug unexpected calls
        Rlog.d(RILJ_LOG_TAG, "stack trace as follows", new Exception());

        send(rr);
    }

    /** M: add extra parameter */
    @Override
    public void setInitialAttachApn(String apn, String protocol, int authType, String username,
            String password, Message result) {
        Rlog.e(RILJ_LOG_TAG, "setInitialAttachApn: operatorNumeric is required on MTK!");
        setInitialAttachApn(apn, protocol, authType, username, password, "", false, result);
    }

    @Override
    public void setInitialAttachApn(String apn, String protocol, int authType, String username,
            String password, String operatorNumeric, boolean canHandleIms, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_INITIAL_ATTACH_APN, null);

        if (RILJ_LOGD) riljLog("Set RIL_REQUEST_SET_INITIAL_ATTACH_APN");

        rr.mParcel.writeString(apn);
        rr.mParcel.writeString(protocol);
        rr.mParcel.writeInt(authType);
        rr.mParcel.writeString(username);
        rr.mParcel.writeString(password);

        /** M: start */
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeInt(canHandleIms ? 1 : 0);
        /* M: end */

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ", apn:" + apn + ", protocol:" + protocol + ", authType:" + authType
                + ", username:" + username + ", password:" + password
                + ", operatorNumeric:" + operatorNumeric + ", canHandleIms:" + canHandleIms);

        send(rr);
    }

    @Override
    public void setRadioCapability(RadioCapability rc, Message response) {
        RILRequest rr = RILRequest.obtain(
                RIL_REQUEST_SET_RADIO_CAPABILITY, response);

        rr.mParcel.writeInt(rc.getVersion());
        rr.mParcel.writeInt(rc.getSession());
        rr.mParcel.writeInt(rc.getPhase());
        rr.mParcel.writeInt(rc.getRadioAccessFamily());
        rr.mParcel.writeString(rc.getLogicalModemUuid());
        rr.mParcel.writeInt(rc.getStatus());

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + rc.toString());
        }

        send(rr);
    }

    @Override
    public void getRadioCapability(Message response) {
        RILRequest rr = RILRequest.obtain(
                RIL_REQUEST_GET_RADIO_CAPABILITY, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void connectRilSocket() {
        if (RILJ_LOGD) {
            riljLog("[RIL SWITCH]reconnectRilSocket()");
        }
        if (mReceiverThread == null && mReceiver == null) {
            connectRild();
        } else {
            if (RILJ_LOGD) {
                riljLog("[RIL SWITCH] Already connected, abort connect request.");
            }
        }
    }

    @Override
    public void disconnectRilSocket() {
        if (RILJ_LOGD) {
            riljLog("[RIL SWITCH]disconnectRilSocket()");
        }
        if (mSenderThread != null) {
            mSenderThread.getLooper().quit();
            mSenderThread = null;
        }
        if (mReceiver != null) {
            ((MTKRILReceiver) mReceiver).mStoped = true;
        }

        try {
            if (mSocket != null) {
                mSocket.shutdownInput();
            }
            if (mReceiverThread != null) {
                while (mReceiverThread.isAlive()) {
                    riljLog("[RIL SWITCH]mReceiverThread.isAlive() = true;");
                    Thread.sleep(500);
                }
            }
            mReceiverThread = null;
            mReceiver = null;
            // Set mRilVersion to -1, it will not notifyRegistrant in registerForRilConnected.
            mRilVersion = -1;
        } catch (IOException ex) {
            if (RILJ_LOGD) {
                riljLog("[RIL SWITCH]IOException ex = " + ex);
            }
        } catch (InterruptedException er) {
            if (RILJ_LOGD) {
                riljLog("[RIL SWITCH]InterruptedException er = " + er);
            }
        }
    }

    /* M: SS part */
    public void
    changeBarringPassword(String facility, String oldPwd, String newPwd,
        String newCfm, Message result) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(4);
        rr.mParcel.writeString(facility);
        rr.mParcel.writeString(oldPwd);
        rr.mParcel.writeString(newPwd);
        rr.mParcel.writeString(newCfm);
        send(rr);
    }

    public void setCLIP(boolean enable, Message result) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_CLIP, result, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_CLIP, result);

        // count ints
        rr.mParcel.writeInt(1);

        if (enable) {
            rr.mParcel.writeInt(1);
        } else {
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + enable);

        send(rr);
    }
    /* M: SS part end */

    /* M: Network part start */
    public String lookupOperatorNameFromNetwork(long subId, String numeric, boolean desireLongName) {
        int phoneId = SubscriptionManager.getPhoneId((int) subId);
        String nitzOperatorNumeric = null;
        String nitzOperatorName = null;

        nitzOperatorNumeric = TelephonyManager.getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_NITZ_OPER_CODE, "");
        if ((numeric != null) && (numeric.equals(nitzOperatorNumeric))) {
            if (desireLongName == true) {
                nitzOperatorName = TelephonyManager.getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_NITZ_OPER_LNAME, "");
            } else {
                nitzOperatorName = TelephonyManager.getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_NITZ_OPER_SNAME, "");
            }
        }

        /* ALPS00273663 handle UCS2 format name : prefix + hex string ex: "uCs2806F767C79D1" */
        if ((nitzOperatorName != null) && (nitzOperatorName.startsWith("uCs2") == true))
        {
            riljLog("lookupOperatorNameFromNetwork handling UCS2 format name");
            try {
                nitzOperatorName = new String(IccUtils.hexStringToBytes(nitzOperatorName.substring(4)), "UTF-16");
            } catch (UnsupportedEncodingException ex) {
                riljLog("lookupOperatorNameFromNetwork UnsupportedEncodingException");
            }
        }

        riljLog("lookupOperatorNameFromNetwork numeric= " + numeric + ",subId= " + subId + ",nitzOperatorNumeric= " + nitzOperatorNumeric + ",nitzOperatorName= " + nitzOperatorName);

        return nitzOperatorName;
    }

    @Override
    public void
    setNetworkSelectionModeManualWithAct(String operatorNumeric, String act, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + operatorNumeric + "" + act);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeString(act);
        rr.mParcel.writeString("0"); //the 3rd parameter is for MTK RIL to identify it shall be processed as semi auto network selection mode or not

        send(rr);
    }

    private Object
    responseNetworkInfoWithActs(Parcel p) {
        String strings[] = (String []) responseStrings(p);
        ArrayList<NetworkInfoWithAcT> ret;

        // MTK TODO: duplication on top of duplication... refactor this later (sigh)
        // SpnOverride spnOverride = new SpnOverride();

        if (strings.length % 4 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_GET_POL_LIST: invalid response. Got "
                + strings.length + " strings, expected multible of 5");
        }

        ret = new ArrayList<NetworkInfoWithAcT>(strings.length / 4);

        String strOperName = null;
        String strOperNumeric = null;
        int nAct = 0;
        int nIndex = 0;

        for (int i = 0 ; i < strings.length ; i += 4) {
            strOperName = null;
            strOperNumeric = null;
            if (strings[i] != null) {
                nIndex = Integer.parseInt(strings[i]);
            } else {
                Rlog.d(RILJ_LOG_TAG, "responseNetworkInfoWithActs: no invalid index. i is " + i);
            }

            if (strings[i + 1] != null) {
                int format = Integer.parseInt(strings[i + 1]);
                switch (format) {
                    case 0:
                    case 1:
                        strOperName = strings[i + 2];
                        break;
                    case 2:
                        if (strings[i + 2] != null) {
                            // xen0n: use CM SpnOverride impl
                            final String operNumeric = strings[i + 2];
                            strOperNumeric = operNumeric;
                            if (mSpnOverride.containsCarrier(operNumeric)) {
                                strOperName = mSpnOverride.getSpn(operNumeric);
                            }
                        }
                        break;
                    default:
                        break;
                }
            }

            if (strings[i + 3] != null) {
                nAct = Integer.parseInt(strings[i + 3]);
            } else {
                Rlog.d(RILJ_LOG_TAG, "responseNetworkInfoWithActs: no invalid Act. i is " + i);
            }
            if (strOperNumeric != null && !strOperNumeric.equals("?????")) {
                ret.add(
                    new NetworkInfoWithAcT(
                        strOperName,
                        strOperNumeric,
                        nAct,
                        nIndex));
            } else {
                Rlog.d(RILJ_LOG_TAG, "responseNetworkInfoWithActs: invalid oper. i is " + i);
            }
        }

        return ret;
    }

    public void
    setNetworkSelectionModeSemiAutomatic(String operatorNumeric, String act, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + operatorNumeric + "" + act);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeString(act);
        rr.mParcel.writeString("1"); //the 3rd parameter is for MTK RIL to identify it shall be processed as semi auto network selection mode

        send(rr);
    }

    public void getPOLCapabilty(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_POL_CAPABILITY, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void getCurrentPOLList(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_POL_LIST, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void setPOLEntry(int index, String numeric, int nAct, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_POL_ENTRY, response);
        if (numeric == null || (numeric.length() == 0)) {
            rr.mParcel.writeInt(1);
            rr.mParcel.writeString(Integer.toString(index));
        } else {
            rr.mParcel.writeInt(3);
            rr.mParcel.writeString(Integer.toString(index));
            rr.mParcel.writeString(numeric);
            rr.mParcel.writeString(Integer.toString(nAct));
        }
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    // Femtocell (CSG) feature START
    public void getFemtoCellList(String operatorNumeric, int rat, Message response) {
        RILRequest rr
        = RILRequest.obtain(RIL_REQUEST_GET_FEMTOCELL_LIST,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeString(Integer.toString(rat));
        send(rr);
    }

    public void abortFemtoCellList(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ABORT_FEMTOCELL_LIST, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void selectFemtoCell(FemtoCellInfo femtocell, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SELECT_FEMTOCELL,
                                    response);
        int act = femtocell.getCsgRat();

        if (act == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
            act = 7;
        } else if (act == ServiceState.RIL_RADIO_TECHNOLOGY_UMTS) {
            act = 2;
        } else {
            act = 0;
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " csgId=" + femtocell.getCsgId() + " plmn=" + femtocell.getOperatorNumeric() + " rat=" + femtocell.getCsgRat() + " act=" + act);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(femtocell.getOperatorNumeric());
        rr.mParcel.writeString(Integer.toString(act));
        rr.mParcel.writeString(Integer.toString(femtocell.getCsgId()));

        send(rr);
    }
    // Femtocell (CSG) feature END

    // M: CC33 LTE.
    @Override
    public void
    setDataOnToMD(boolean enable, Message result) {
        //AT+EDSS = <on/off>
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_DATA_ON_TO_MD, result);
        int type = enable ? 1 : 0;
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(type);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                                + requestToString(rr.mRequest) + ": " + type);
        send(rr);
    }

    @Override
    public void
    setRemoveRestrictEutranMode(boolean enable, Message result) {
        //AT+ECODE33 = <on/off>
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE, result);
        int type = enable ? 1 : 0;
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(type);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                                + requestToString(rr.mRequest) + ": " + type);
        send(rr);
    }

    public boolean isGettingAvailableNetworks() {
        synchronized (mRequestList) {
            for (int i = 0, s = mRequestList.size() ; i < s ; i++) {
                RILRequest rr = mRequestList.valueAt(i);
                if (rr != null &&
                    (rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS ||
                     rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT)) {
                    return true;
                }
            }
        }

        return false;
    }

    /* M: Network part end */
    // IMS
    public void setIMSEnabled(boolean enable, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_IMS_ENABLE, response);

        rr.mParcel.writeInt(1);
        if (enable) {
            rr.mParcel.writeInt(1);
        } else {
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }
    // VOLTE
    public void setupDedicateDataCall(int ddcId, int interfaceId, boolean signalingFlag, QosStatus qosStatus, TftStatus tftStatus, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_SETUP_DEDICATE_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SETUP_DEDICATE_DATA_CALL, response);

        rr.mParcel.writeInt(7);
        rr.mParcel.writeInt(ddcId);
        rr.mParcel.writeInt(interfaceId);
        rr.mParcel.writeInt(signalingFlag ? 1 : 0);
        if (qosStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            qosStatus.writeTo(rr.mParcel);
        }

        if (tftStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            tftStatus.writeTo(rr.mParcel);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + " interfaceId=" + interfaceId + " signalingFlag="
                + signalingFlag);

        send(rr);
    }

    public void deactivateDedicateDataCall(int cid, String reason, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL, response);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(Integer.toString(cid));
        rr.mParcel.writeString(reason);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " +
                requestToString(rr.mRequest) + " " + cid + " " + reason);

        send(rr);
    }

    public void modifyDataCall(int cid, QosStatus qosStatus, TftStatus tftStatus, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_MODIFY_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_MODIFY_DATA_CALL, response);
        rr.mParcel.writeInt(7);
        rr.mParcel.writeInt(cid);
        if (qosStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            qosStatus.writeTo(rr.mParcel);
        }

        if (tftStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            tftStatus.writeTo(rr.mParcel);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void abortSetupDataCall(int ddcId, String reason, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_ABORT_SETUP_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ABORT_SETUP_DATA_CALL, response);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(Integer.toString(ddcId));
        rr.mParcel.writeString(reason);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + ddcId + " " + reason);
        send(rr);
    }

    public void pcscfDiscoveryPco(int cid, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_PCSCF_DISCOVERY_PCO, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_PCSCF_DISCOVERY_PCO, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(cid);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void clearDataBearer(Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_CLEAR_DATA_BEARER, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CLEAR_DATA_BEARER, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    // M: Fast Dormancy
    public void setScri(boolean forceRelease, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SCRI, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(forceRelease ? 1 : 0);

        send(rr);

    }

    //[New R8 modem FD]
    public void setFDMode(int mode, int parameter1, int parameter2, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_FD_MODE, response);

        //AT+EFD=<mode>[,<param1>[,<param2>]]
        //mode=0:disable modem Fast Dormancy; mode=1:enable modem Fast Dormancy
        //mode=3:inform modem the screen status; parameter1: screen on or off
        //mode=2:Fast Dormancy inactivity timer; parameter1:timer_id; parameter2:timer_value
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        if (mode == 0 || mode == 1) {
            rr.mParcel.writeInt(1);
            rr.mParcel.writeInt(mode);
        } else if (mode == 3) {
            rr.mParcel.writeInt(2);
            rr.mParcel.writeInt(mode);
            rr.mParcel.writeInt(parameter1);
        } else if (mode == 2) {
            rr.mParcel.writeInt(3);
            rr.mParcel.writeInt(mode);
            rr.mParcel.writeInt(parameter1);
            rr.mParcel.writeInt(parameter2);
        }

        send(rr);

    }

    // @argument:
    // enable: yes   -> data centric
    //         false -> voice centric
    public void setDataCentric(boolean enable, Message response) {
    	if (RILJ_LOGD) riljLog("setDataCentric");
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_DATA_CENTRIC, response);

        rr.mParcel.writeInt(1);
        if(enable) {
            rr.mParcel.writeInt(1);
        } else {
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }


    /// M: CC010: Add RIL interface @{
    /**
     * Notify modem about IMS call status.
     * @param existed True if there is at least one IMS call existed, else return false.
     * @param response User-defined message code.
     */
    @Override
    public void setImsCallStatus (boolean existed, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_IMS_CALL_STATUS, null);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(existed ? 1 : 0);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }
    /// @}

    /// M: CC072: Add Customer proprietary-IMS RIL interface. @{
    /**
     * Transfer IMS call to CS modem.
     *
     * @param numberOfCall The number of call
     * @param callList IMS call context
     */
     @Override
     public void setSrvccCallContextTransfer(int numberOfCall, SrvccCallContext[] callList) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SRVCC_CALL_CONTEXT_TRANSFER, null);

        if ((numberOfCall <= 0) || (callList == null)) {
              return;
        }

        rr.mParcel.writeInt(numberOfCall * 9 + 1);
        rr.mParcel.writeString(Integer.toString(numberOfCall));
        for (int i = 0; i < numberOfCall; i++) {
            rr.mParcel.writeString(Integer.toString(callList[i].getCallId()));
            rr.mParcel.writeString(Integer.toString(callList[i].getCallMode()));
            rr.mParcel.writeString(Integer.toString(callList[i].getCallDirection()));
            rr.mParcel.writeString(Integer.toString(callList[i].getCallState()));
            rr.mParcel.writeString(Integer.toString(callList[i].getEccCategory()));
            rr.mParcel.writeString(Integer.toString(callList[i].getNumberType()));
            rr.mParcel.writeString(callList[i].getNumber());
            rr.mParcel.writeString(callList[i].getName());
            rr.mParcel.writeString(Integer.toString(callList[i].getCliValidity()));
        }

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
     }

     /**
     * Update IMS registration status to modem.
     *
     * @param regState IMS registration state
     *                 0: IMS unregistered
     *                 1: IMS registered
     * @param regType  IMS registration type
     *                 0: Normal IMS registration
     *                 1: Emergency IMS registration
     * @param reason   The reason of state transition from registered to unregistered
     *                 0: Unspecified
     *                 1: Power off
     *                 2: RF off
     */
     public void updateImsRegistrationStatus(int regState, int regType, int reason) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_UPDATE_IMS_REGISTRATION_STATUS, null);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeInt(regState);
        rr.mParcel.writeInt(regType);
        rr.mParcel.writeInt(reason);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
     }
     /// @}

    /* M: C2K part start */
    @Override
    public void setViaTRM(int mode, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_VIA_TRM, null);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(mode);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void getNitzTime(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_GET_NITZ_TIME, result);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void requestSwitchHPF(boolean enableHPF, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SWITCH_HPF, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + enableHPF);
        }

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enableHPF ? 1 : 0);

        send(rr);
    }

    @Override
    public void setAvoidSYS(boolean avoidSYS, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_AVOID_SYS, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + avoidSYS);
        }

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(avoidSYS ? 1 : 0);

        send(rr);
    }

    @Override
    public void getAvoidSYSList(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_AVOID_SYS, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void queryCDMANetworkInfo(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_CDMA_NETWORK_INFO, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void setOplmn(String oplmnInfo, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SEND_OPLMN, response);
        rr.mParcel.writeString(oplmnInfo);
        riljLog("sendOplmn, OPLMN is" + oplmnInfo);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void getOplmnVersion(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_OPLMN_VERSION, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void requestAGPSTcpConnected(int connected, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_AGPS_TCP_CONNIND, result);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(connected);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + connected);
        }
        send(rr);
    }

    @Override
    public void requestAGPSSetMpcIpPort(String ip, String port, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_AGPS_SET_MPC_IPPORT, result);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(ip);
        rr.mParcel.writeString(port);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " : " + ip + ", " + port);
        }
        send(rr);
    }

    @Override
    public void requestAGPSGetMpcIpPort(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_AGPS_GET_MPC_IPPORT, result);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void requestSetEtsDev(int dev, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_ETS_DEV, result);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(dev);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + dev);
        }
        send(rr);
    }

    @Override
    public void setArsiReportThreshold(int threshold, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_ARSI_THRESHOLD, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(threshold);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " : " + threshold);
        }

        send(rr);
    }

    @Override
    public void queryCDMASmsAndPBStatus(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void queryCDMANetWorkRegistrationState(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_NETWORK_REGISTRATION, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void setMeid(String meid, Message response) {
        RILRequest rr
               = RILRequest.obtain(RIL_REQUEST_SET_MEID, response);

       rr.mParcel.writeString(meid);
       if (RILJ_LOGD) {
           riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + meid);
       }

       send(rr);
   }

    @Override
    public void setMdnNumber(String mdn, Message response) {
         RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_WRITE_MDN, response);

        rr.mParcel.writeString(mdn);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + mdn);
        }

        send(rr);
    }

    /// M: UTK started @{
    @Override
    public void getUtkLocalInfo(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_LOCAL_INFO, result);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void requestUtkRefresh(int refreshType, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_UTK_REFRESH, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(refreshType);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void reportUtkServiceIsRunning(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING, result);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void profileDownload(String profile, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_STK_SET_PROFILE, response);

        rr.mParcel.writeString(profile);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void handleCallSetupRequestFromUim(boolean accept, Message response) {
        RILRequest rr = RILRequest.obtain(
            RILConstants.RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM,
            response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(accept ? 1 : 0);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + (accept ? 1 : 0));
        }

        send(rr);
    }
    /// UTK end @}

    ///M: [C2K][SVLTE] Removt SIM access feature @{
    @Override
    public void configModemStatus(int modemStatus, int remoteSimProtocol, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CONFIG_MODEM_STATUS, result);

        // count ints
        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(modemStatus);
        rr.mParcel.writeInt(remoteSimProtocol);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + modemStatus + ", " + remoteSimProtocol);
        }

        send(rr);
    }
    /// @}

    /// M: [C2K][SVLTE] C2K SVLTE CDMA RAT control @{
    @Override
    public void configIratMode(int iratMode, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CONFIG_IRAT_MODE, result);

        // count ints
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(iratMode);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + iratMode + ", " + iratMode);
        }

        send(rr);
    }
    /// @}

    /// M: [C2K][SVLTE] C2K SVLTE CDMA eHPRD control @{
    @Override
    public void configEvdoMode(int evdoMode, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CONFIG_EVDO_MODE, result);

        // count ints
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(evdoMode);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + evdoMode);
        }

        send(rr);
    }
    /// @}

    ///M: [C2K][IRAT] code start @{
    @Override
    public void confirmIratChange(int apDecision, Message response) {
        RILRequest rr = RILRequest.obtain(RILConstants.RIL_REQUEST_CONFIRM_INTER_3GPP_IRAT_CHANGE,
                response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(apDecision);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + apDecision);
        }
        send(rr);
    }

    @Override
    public void requestSetPsActiveSlot(int psSlot, Message response) {
        RILRequest rr = RILRequest.obtain(RILConstants.RIL_REQUEST_SET_ACTIVE_PS_SLOT, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(psSlot);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + psSlot);
        }
        send(rr);
    }

    @Override
    public void syncNotifyDataCallList(AsyncResult dcList) {
        riljLog("[C2K_IRAT_RIL] notify data call list!");
        mDataNetworkStateRegistrants.notifyRegistrants(dcList);
    }

    @Override
    public void requestDeactivateLinkDownPdn(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_DEACTIVATE_LINK_DOWN_PDN, response);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    private Object responseIratStateChange(Parcel p) {
        MdIratInfo pdnIratInfo = new MdIratInfo();
        pdnIratInfo.sourceRat = p.readInt();
        pdnIratInfo.targetRat = p.readInt();
        pdnIratInfo.action = p.readInt();
        pdnIratInfo.type = IratType.getIratTypeFromInt(p.readInt());
        riljLog("[C2K_IRAT_RIL]responseIratStateChange: pdnIratInfo = " + pdnIratInfo);
        return pdnIratInfo;
    }
    ///@} [C2K] IRAT code end

    /// M: [C2K][SVLTE] Set the SVLTE RAT mode. @{
    @Override
    public void setSvlteRatMode(int radioTechMode, int preSvlteMode, int svlteMode,
            int preRoamingMode, int roamingMode, boolean is3GDualModeCard, Message response) {
        RILRequest rr = RILRequest.obtain(RILConstants.RIL_REQUEST_SET_SVLTE_RAT_MODE, response);
        rr.mParcel.writeInt(6);
        rr.mParcel.writeInt(radioTechMode);
        rr.mParcel.writeInt(preSvlteMode);
        rr.mParcel.writeInt(svlteMode);
        rr.mParcel.writeInt(preRoamingMode);
        rr.mParcel.writeInt(roamingMode);
        rr.mParcel.writeInt(is3GDualModeCard ? 1 : 0);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " radioTechMode: " + radioTechMode
                    + " preSvlteMode: " + preSvlteMode + " svlteMode: " + svlteMode
                    + " preRoamingMode: " + preRoamingMode + " roamingMode: " + roamingMode
                    + " is3GDualModeCard: " + is3GDualModeCard);
        }
        send(rr);
    }
    /// M: [C2K][SVLTE] Set the SVLTE RAT mode. @}

    /// M: [C2K][SVLTE] Set the STK UTK mode. @}
    @Override
    public void setStkUtkMode(int stkUtkMode, Message response) {
        RILRequest rr = RILRequest.obtain(RILConstants.RIL_REQUEST_SET_STK_UTK_MODE, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(stkUtkMode);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " stkUtkMode: " + stkUtkMode);
        }
        send(rr);
    }
    /// M: [C2K][SVLTE] Set the STK UTK mode. @}

    /// M: [C2K][SVLTE] Update RIL instance id for SVLTE switch ActivePhone. @{
    @Override
    public void setInstanceId(int instanceId) {
        mInstanceId = instanceId;
    }
    /// @}

    /// M: [C2K][IR] Support SVLTE IR feature. @{

    @Override
    public void setRegistrationSuspendEnabled(int enabled, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_REG_SUSPEND_ENABLED, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enabled);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void setResumeRegistration(int sessionId, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_RESUME_REGISTRATION, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(sessionId);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void setCdmaRegistrationSuspendEnabled(boolean enabled, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_REG_SUSPEND_ENABLED_CDMA, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enabled ? 1 : 0);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " enable=" + enabled);
        }
        send(rr);
    }

    @Override
    public void setResumeCdmaRegistration(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_RESUME_REGISTRATION_CDMA, response);
        mVoiceNetworkStateRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    /// M: [C2K][IR] Support SVLTE IR feature. @}

    /* M: C2K part end */

    //[ALPS01810775,ALPS01868743]-Start
    public int getDisplayState(){
        return mDefaultDisplayState;
    }
    //[ALPS01810775,ALPS01868743]-End

    // M: [C2K] AP IRAT start.
    @Override
    public void requestTriggerLteBgSearch(int numOfArfcn, int[] arfcn, Message response) {
        RILRequest rr = RILRequest.obtain(RILConstants.RIL_REQUEST_TRIGGER_LTE_BG_SEARCH,
                response);
        int len = arfcn.length;
        rr.mParcel.writeInt(len + 1);
        rr.mParcel.writeInt(numOfArfcn);
        for (int i = 0; i < len; i++) {
            rr.mParcel.writeInt(arfcn[i]);
        }
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " length of arfcn" + len);
        }

        send(rr);
    }

    @Override
    public void requestSetLteEarfcnEnabled(boolean enable, Message response) {
        RILRequest rr = RILRequest.obtain(RILConstants.RIL_REQUEST_SET_LTE_EARFCN_ENABLED,
                response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enable ? 1 : 0);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " enable = " + enable);
        }

        send(rr);
    }
    // M: [C2K] AP IRAT end.

    // M: [C2K] SVLTE Remote SIM Access start.
    private int getFullCardType(int slot) {
        String cardType;
        if (slot == 0) {
            Rlog.d(RILJ_LOG_TAG, "getFullCardType slot0");
            cardType = SystemProperties.get(PROPERTY_RIL_FULL_UICC_TYPE[0]);
        } else if (slot == 1) {
            Rlog.d(RILJ_LOG_TAG, "getFullCardType slot1");
            cardType = SystemProperties.get(PROPERTY_RIL_FULL_UICC_TYPE[1]);
        } else {
            Rlog.d(RILJ_LOG_TAG, "getFullCardType invalid slotId = " + slot);
            return 0;
        }
        
        Rlog.d(RILJ_LOG_TAG, "getFullCardType=" + cardType);
        String appType[] = cardType.split(",");
        int fullType = 0;
        for (int i = 0; i < appType.length; i++) {
            if ("USIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_USIM;
            } else if ("SIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_SIM;
            } else if ("CSIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_CSIM;
            } else if ("RUIM".equals(appType[i])) {
                fullType = fullType | CARD_TYPE_RUIM;
            }
        }
        Rlog.d(RILJ_LOG_TAG, "fullType=" + fullType);
        return fullType;
    }

    private class ConfigModemRunnable implements Runnable {
        public  ConfigModemRunnable() {
        }

        @Override
        public void run() {
            configModemRemoteSimAccess();
        }
    }
    ConfigModemRunnable configModemRunnable = new ConfigModemRunnable();

    private void configModemRemoteSimAccess() {
        String cardTypeSet = SystemProperties.get(PROPERTY_RIL_CARD_TYPE_SET, "0");
        String cardTypeSet_2 = SystemProperties.get(PROPERTY_RIL_CARD_TYPE_SET_2, "0");
        String md3State = SystemProperties.get(PROPERTY_NET_CDMA_MDMSTAT, "not ready");
        Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: cardTypeSet=" + cardTypeSet + "  cardTypeSet_2=" + cardTypeSet_2 + "  md3State=" + md3State);
        if (!cardTypeSet.equals("1") || !cardTypeSet_2.equals("1") || !md3State.equals("ready")) {
            mHandler.postDelayed(configModemRunnable, INITIAL_RETRY_INTERVAL_MSEC);
            return;
        }

        int fullType = getFullCardType(0);

        // handle special case for solution1 slot2:CDMA
        int fullType_2 = getFullCardType(1);
        int capability = SystemProperties.getInt(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, 1);
        Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: capability=" + capability);
        boolean md3AccessProtocol2 = false;
        if (((fullType_2 & CARD_TYPE_CSIM) == CARD_TYPE_CSIM || (fullType_2 & CARD_TYPE_RUIM) == CARD_TYPE_RUIM)//slot2:CDMA
                && capability != 1) {// ES3G=2
            Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: md3AccessProtocol2 = true!");
            md3AccessProtocol2 = true;
        }

        if (fullType == 0) {
            // no card
            Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: no card");
            if (md3AccessProtocol2) {
                configModemStatus(1, 2, null);
            } else {
                configModemStatus(1, 1, null);
            }
        } else if ((fullType & CARD_TYPE_RUIM) == 0 && (fullType & CARD_TYPE_CSIM) == 0) {
            // GSM only
            Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: GSM only");
            configModemStatus(0, 1, null);
        } else if (((fullType & CARD_TYPE_SIM) == 0 && (fullType & CARD_TYPE_USIM) == 0) ||
                ((fullType & CARD_TYPE_SIM) == CARD_TYPE_SIM && (fullType & CARD_TYPE_RUIM) == CARD_TYPE_RUIM)) {
            // CDMA only
            // 1. no SIM and no USIM
            // 2. RUIM and SIM (CT 3G card)
            Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: CDMA only");
            if (md3AccessProtocol2) {
                configModemStatus(1, 2, null);
            } else {
                configModemStatus(1, 1, null);
            }
        } else if ((fullType & CARD_TYPE_USIM) == CARD_TYPE_USIM && (fullType & CARD_TYPE_CSIM) == CARD_TYPE_CSIM) {
            // CT LTE
            Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: CT LTE");
            if (md3AccessProtocol2) {
                configModemStatus(2, 2, null);
            } else {
                configModemStatus(2, 1, null);
            }
        } else {
            //other case, may not happen!
            Rlog.d(RILJ_LOG_TAG, "RIL configModemStatus: other case, may not happen!");
        }
    }

    /**
     * Set the xTK mode.
     * @param mode The xTK mode.
     */
    public void setStkSwitchMode(int mode) { // Called by SvlteRatController
        if (RILJ_LOGD) {
            riljLog("setStkSwitchMode=" + mode + " old value=" + mStkSwitchMode);
        }
        mStkSwitchMode = mode;
    }

    /**
     * Set the UTK Bip Ps type .
     * @param mBipPsType The Bip type.
     */
    public void setBipPsType(int type) { // Called by SvltePhoneProxy
        if (RILJ_LOGD) {
            riljLog("setBipPsType=" + type + " old value=" + mBipPsType);
        }
        mBipPsType = type;
    }
    // M: [C2K] SVLTE Remote SIM Access end.

    /**
     * Switch antenna.
     * @param callState call state, 0 means call disconnected and 1 means call established.
     * @param ratMode RAT mode, 0 means GSM and 7 means C2K.
     */
    @Override
    public void switchAntenna(int callState, int ratMode) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SWITCH_ANTENNA, null);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(callState);
        rr.mParcel.writeInt(ratMode);

        if (RILJ_LOGD) {
            riljLog(rr.serialString()
                + "> " + requestToString(rr.mRequest) + " callState: " + callState
                + ", ratMode:" + ratMode);
        }

        send(rr);
    }

    private static int readRilMessage(InputStream is, byte[] buffer)
            throws IOException {
        int countRead;
        int offset;
        int remaining;
        int messageLength;

        // First, read in the length of the message
        offset = 0;
        remaining = 4;
        do {
            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0 ) {
                Rlog.e(RILJ_LOG_TAG, "Hit EOS reading message length");
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        messageLength = ((buffer[0] & 0xff) << 24)
                | ((buffer[1] & 0xff) << 16)
                | ((buffer[2] & 0xff) << 8)
                | (buffer[3] & 0xff);

        // Then, re-use the buffer and read in the message itself
        offset = 0;
        remaining = messageLength;
        do {
            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0 ) {
                Rlog.e(RILJ_LOG_TAG, "Hit EOS reading message.  messageLength=" + messageLength
                        + " remaining=" + remaining);
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        return messageLength;
    }

	protected RILReceiver createRILReceiver() {
        return new MTKRILReceiver();
    }

	protected class MTKRILReceiver extends RILReceiver {
        byte[] buffer;

        // MTK
        /// M: For SVLTE to disconnect socket in C2K only mode.
        boolean mStoped = false;

        protected MTKRILReceiver() {
            buffer = new byte[RIL_MAX_COMMAND_BYTES];
        }

        @Override
        public void
        run() {
            int retryCount = 0;
            String rilSocket = "rild";

            try {for (;;) {
                // MTK
                /// M: For SVLTE to disconnect socket in C2K only mode.
                if (mStoped) {
                    riljLog("[RIL SWITCH] stoped now!");
                    return;
                }

                LocalSocket s = null;
                LocalSocketAddress l;

                /// M: If SVLTE support, LTE RIL ID is a special value, force connect to rild socket
                riljLog(
                        "mInstanceId=" + mInstanceId +
                        " mPreferredNetworkType=" + mPreferredNetworkType +
                        " phoneType=" + TelephonyManager.getPhoneType(mPreferredNetworkType) +
                        " SvlteUtils.isValidPhoneId ret=" + SvlteUtils.isValidPhoneId(mInstanceId) +
                        " SvlteUtils.getSlotId ret=" + SvlteUtils.getSlotId(mInstanceId));
                if (mInstanceId == null || SvlteUtils.isValidPhoneId(mInstanceId)) {
                    rilSocket = SOCKET_NAME_RIL[SvlteUtils.getSlotId(mInstanceId)];
                } else {
                    if (SystemProperties.getInt("ro.mtk_dt_support", 0) != 1) {
                        // dsds
                        rilSocket = SOCKET_NAME_RIL[mInstanceId];
                    } else {
                        // dsda
                        if (SystemProperties.getInt("ro.evdo_dt_support", 0) == 1) {
                            // c2k dsda
                            rilSocket = SOCKET_NAME_RIL[mInstanceId];
                        } else if (SystemProperties.getInt("ro.telephony.cl.config", 0) == 1) {
                            // for C+L
                            rilSocket = SOCKET_NAME_RIL[mInstanceId];
                        } else {
                            // gsm dsda
                            rilSocket = "rild-md2";
                        }
                    }
                }

                /* M: C2K start */
                int phoneType = TelephonyManager.getPhoneType(mPreferredNetworkType);
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    rilSocket = C2K_SOCKET_NAME_RIL;
                }
                /* M: C2K end */

                // xen0n: hardcode SUB10 and friends
                if (mInstanceId != null) {
                    if (mInstanceId == 10) {
                        rilSocket = SOCKET_NAME_RIL[0];
                    } else if (mInstanceId == 11) {
                        // TODO: what to do in this case?
                    }
                }

                riljLog("rilSocket[" + mInstanceId + "] = " + rilSocket);

                try {
                    s = new LocalSocket();
                    l = new LocalSocketAddress(rilSocket,
                            LocalSocketAddress.Namespace.RESERVED);
                    s.connect(l);
                } catch (IOException ex){
                    try {
                        if (s != null) {
                            s.close();
                        }
                    } catch (IOException ex2) {
                        //ignore failure to close after failure to connect
                    }

                    // don't print an error message after the the first time
                    // or after the 8th time

                    if (retryCount == 8) {
                        Rlog.e (RILJ_LOG_TAG,
                            "Couldn't find '" + rilSocket
                            + "' socket after " + retryCount
                            + " times, continuing to retry silently");
                    } else if (retryCount >= 0 && retryCount < 8) {
                        Rlog.i (RILJ_LOG_TAG,
                            "Couldn't find '" + rilSocket
                            + "' socket; retrying after timeout");
                    }

                    try {
                        Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                    } catch (InterruptedException er) {
                    }

                    retryCount++;
                    continue;
                }

                retryCount = 0;

                mSocket = s;
                Rlog.i(RILJ_LOG_TAG, "(" + mInstanceId + ") Connected to '"
                        + rilSocket + "' socket");

                /* Compatibility with qcom's DSDS (Dual SIM) stack */
                if (needsOldRilFeature("qcomdsds")) {
                    String str = "SUB1";
                    byte[] data = str.getBytes();
                    try {
                        mSocket.getOutputStream().write(data);
                        Rlog.i(RILJ_LOG_TAG, "Data sent!!");
                    } catch (IOException ex) {
                            Rlog.e(RILJ_LOG_TAG, "IOException", ex);
                    } catch (RuntimeException exc) {
                        Rlog.e(RILJ_LOG_TAG, "Uncaught exception ", exc);
                    }
                }

                int length = 0;
                try {
                    InputStream is = mSocket.getInputStream();

                    for (;;) {
                        Parcel p;

                        length = readRilMessage(is, buffer);

                        if (length < 0) {
                            // End-of-stream reached
                            break;
                        }

                        p = Parcel.obtain();
                        p.unmarshall(buffer, 0, length);
                        p.setDataPosition(0);

                        //Rlog.v(RILJ_LOG_TAG, "Read packet: " + length + " bytes");

                        processResponse(p);
                        p.recycle();
                    }
                } catch (java.io.IOException ex) {
                    Rlog.i(RILJ_LOG_TAG, "'" + rilSocket + "' socket closed",
                          ex);
                } catch (Throwable tr) {
                    Rlog.e(RILJ_LOG_TAG, "Uncaught exception read length=" + length +
                        "Exception:" + tr.toString());
                }

                Rlog.i(RILJ_LOG_TAG, "(" + mInstanceId + ") Disconnected from '" + rilSocket
                      + "' socket");

                setRadioState (RadioState.RADIO_UNAVAILABLE);

                try {
                    mSocket.close();
                } catch (IOException ex) {
                }

                mSocket = null;
                RILRequest.resetSerial();

                // Clear request list on close
                clearRequestList(RADIO_NOT_AVAILABLE, false);
                if (configModemRunnable != null) {
                    Rlog.i(RILJ_LOG_TAG, "clear configModemRunnable");
                    mHandler.removeCallbacks(configModemRunnable);
                }
            }} catch (Throwable tr) {
                Rlog.e(RILJ_LOG_TAG,"Uncaught exception", tr);
            }

            /* We're disconnected so we don't know the ril version */
            notifyRegistrantsRilConnectionChanged(-1);
        }
    }
}
