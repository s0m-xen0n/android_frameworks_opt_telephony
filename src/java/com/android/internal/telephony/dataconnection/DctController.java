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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;

import android.os.AsyncResult;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.MultiSimVariants;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.os.SomeArgs;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.dataconnection.DcSwitchAsyncChannel.RequestInfo;
import com.android.internal.util.AsyncChannel;
import com.android.internal.telephony.dataconnection.DdsScheduler;
import com.android.internal.telephony.TelephonyIntents;

import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.dataconnection.DataSubSelector;
import com.mediatek.internal.telephony.ltedc.svlte.IratController;
import com.mediatek.internal.telephony.ltedc.svlte.IratDataSwitchHelper;
import com.mediatek.internal.telephony.ltedc.svlte.MdIratDataSwitchHelper;
import com.mediatek.internal.telephony.ltedc.svlte.SvltePhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public class DctController extends Handler {
    private static final String LOG_TAG = "DctController";
    private static final boolean DBG = true;

    private static final int EVENT_PROCESS_REQUESTS = 100;
    private static final int EVENT_EXECUTE_REQUEST = 101;
    private static final int EVENT_EXECUTE_ALL_REQUESTS = 102;
    private static final int EVENT_RELEASE_REQUEST = 103;
    private static final int EVENT_RELEASE_ALL_REQUESTS = 104;
    private static final int EVENT_START_DDS_SWITCH = 105;

    // MTK
    private static final int EVENT_TRANSIT_TO_ATTACHING = 200;
    private static final int EVENT_CONFIRM_PREDETACH = 201;

    private static final int EVENT_DATA_ATTACHED = 500;
    private static final int EVENT_DATA_DETACHED = 600;

    // MTK
    private static final int EVENT_SET_DATA_ALLOWED = 700;
    private static final int EVENT_RESTORE_PENDING = 800;

    private static final int EVENT_ALL_DATA_DISCONNECTED = 1;
    private static final int EVENT_SET_DATA_ALLOW_DONE = 2;
    private static final int EVENT_DELAYED_RETRY = 3;
    private static final int EVENT_LEGACY_SET_DATA_SUBSCRIPTION = 4;
    private static final int EVENT_SET_DATA_ALLOW_FALSE = 5;

    private RegistrantList mNotifyDefaultDataSwitchInfo = new RegistrantList();
    private RegistrantList mNotifyOnDemandDataSwitchInfo = new RegistrantList();
    private RegistrantList mNotifyOnDemandPsAttach = new RegistrantList();
    private SubscriptionController mSubController = SubscriptionController.getInstance();

    private static DctController sDctController;
    private static boolean isOnDemandDdsSwitchInProgress = false;

    private int mPhoneNum;
    private PhoneProxy[] mPhones;
    private DcSwitchStateMachine[] mDcSwitchStateMachine;
    private DcSwitchAsyncChannel[] mDcSwitchAsyncChannel;
    private Handler[] mDcSwitchStateHandler;
    private HashMap<Integer, RequestInfo> mRequestInfos = new HashMap<Integer, RequestInfo>();
    private Context mContext;

    private AsyncChannel mDdsSwitchPropService;
    private DdsSwitchSerializerHandler mDdsSwitchSerializer;
    private boolean mIsDdsSwitchCompleted = true;

    private final int MAX_RETRY_FOR_ATTACH = 6;
    private final int ATTACH_RETRY_DELAY = 1000 * 10;

    /** Used to send us NetworkRequests from ConnectivityService.  Remember it so we can
     * unregister on dispose. */
    private Messenger[] mNetworkFactoryMessenger;
    private NetworkFactory[] mNetworkFactory;
    private NetworkCapabilities[] mNetworkFilter;

    private SubscriptionManager mSubMgr;

    // MTK
    static final String PROPERTY_RIL_DATA_ICCID = "persist.radio.data.iccid";
    private String[] PROPERTY_ICCID_SIM = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };
    static final String PROPERTY_DATA_ALLOW_SIM = "ril.data.allow";
    static final String PROPERTY_IA_APN_SET_ICCID = "ril.ia.iccid";
    static final String ACTION_SHUTDOWN_IPO = "android.intent.action.ACTION_SHUTDOWN_IPO";
    static final String PROPERTY_TEMP_IA = "ril.radio.ia";
    static final String PROPERTY_TEMP_IA_APN = "ril.radio.ia-apn";

    // / M: [C2K][IRAT]start {@
    private boolean mSuspendNetworkRequest;
    private boolean mHasPendingDataSwitch;

    private IratDataSwitchHelper[] mIratDataSwitchHelper;
    // / M: [C2K][IRAT] end @}

    //M: Pre-Detach Check State
    protected int mUserCnt;
    protected int mTransactionId;

    private DataSubSelector mDataSubSelector;

    /** M: allow data service or not, check setDataAllowed */
    private static boolean mDataAllowed = true;
    protected ConcurrentHashMap<Handler, DcStateParam> mDcSwitchStateChange
            = new ConcurrentHashMap<Handler, DcStateParam>();
    private Runnable mDataNotAllowedTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            logd("disable data service timeout and enable data service again");
            setDataAllowed(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, true, null, 0);
        }
    };

    private BroadcastReceiver defaultDdsBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            logd("got ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED, new DDS = "
                    + intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID));
            updateSubIdAndCapability();
        }
    };

    private BroadcastReceiver subInfoBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            logd("got ACTION_SUBINFO_RECORD_UPDATED");
            updateSubIdAndCapability();
        }
    };

    private void updateSubIdAndCapability() {
        for (int i = 0; i < mPhoneNum; i++) {
           ((TelephonyNetworkFactory)mNetworkFactory[i]).updateNetworkCapability();
        }
    }

    private void releaseAllNetworkRequests() {
        for (int i = 0; i < mPhoneNum; i++) {
            ((TelephonyNetworkFactory)mNetworkFactory[i]).releaseAllNetworkRequests();
        }
    }

    private void releasePendingRequest(NetworkRequest networkRequest) {
        for (int i = 0; i < mPhoneNum; i++) {
            ((TelephonyNetworkFactory)mNetworkFactory[i]).releasePendingRequest(networkRequest);
        }
    }

    private OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            onSubInfoReady();
        }
    };

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            logd("Settings change");
            // MTK
            if (isNetworkRequestSuspend()) {
                mHasPendingDataSwitch = true;
            } else {
                onSettingsChange();
            }
        }
    };

    boolean isActiveSubId(int subId) {
        int[] activeSubs = mSubController.getActiveSubIdList();
        for (int i = 0; i < activeSubs.length; i++) {
            if (subId == activeSubs[i]) {
                return true;
            }
        }
        return false;
    }

    public void updatePhoneObject(PhoneProxy phone) {
        if (phone == null) {
            loge("updatePhoneObject phone = null");
            return;
        }

        PhoneBase phoneBase = (PhoneBase)phone.getActivePhone();
        if (phoneBase == null) {
            loge("updatePhoneObject phoneBase = null");
            return;
        }

        for (int i = 0; i < mPhoneNum; i++) {
            if (mPhones[i] == phone) {
                updatePhoneBaseForIndex(i, phoneBase);
                break;
            }
        }
    }

    private void updatePhoneBaseForIndex(int index, PhoneBase phoneBase) {
        logd("updatePhoneBaseForIndex for phone index=" + index);

        phoneBase.getServiceStateTracker().registerForDataConnectionAttached(mRspHandler,
                   EVENT_DATA_ATTACHED + index, null);
        phoneBase.getServiceStateTracker().registerForDataConnectionDetached(mRspHandler,
                   EVENT_DATA_DETACHED + index, null);

        ConnectivityManager cm = (ConnectivityManager)mPhones[index].getContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE);

        if (mNetworkFactoryMessenger != null) {
            logd("unregister TelephonyNetworkFactory for phone index=" + index);
            cm.unregisterNetworkFactory(mNetworkFactoryMessenger[index]);
            mNetworkFactoryMessenger[index] = null;
            mNetworkFactory[index] = null;
            mNetworkFilter[index] = null;
        }

        mNetworkFilter[index] = new NetworkCapabilities();
        mNetworkFilter[index].addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        // MTK
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_DM);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_WAP);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_NET);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING);
        mNetworkFilter[index].addCapability(NetworkCapabilities.NET_CAPABILITY_RCSE);

        mNetworkFactory[index] = new TelephonyNetworkFactory(this.getLooper(),
                mPhones[index].getContext(), "TelephonyNetworkFactory", phoneBase,
                mNetworkFilter[index]);
        mNetworkFactory[index].setScoreFilter(50);
        mNetworkFactoryMessenger[index] = new Messenger(mNetworkFactory[index]);
        cm.registerNetworkFactory(mNetworkFactoryMessenger[index], "Telephony");

        // MTK
        // M: [C2K] [IRAT] register events using IRAT data switch helper
        // class and listen for IRAT flow. @{
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            logd("[IRAT_DctController] Register for RAT events.");
            mIratDataSwitchHelper[index] =
                    ((SvltePhoneProxy) mPhones[index]).getIratDataSwitchHelper();

            mIratDataSwitchHelper[index].registerForDataConnectionAttached(
                    mRspHandler, EVENT_DATA_ATTACHED + index, null);
            mIratDataSwitchHelper[index].registerForDataConnectionDetached(
                    mRspHandler, EVENT_DATA_DETACHED + index, null);
            mIratDataSwitchHelper[index].registerSetDataAllowed(mRspHandler,
                    EVENT_SET_DATA_ALLOWED + index, null);
        } else {
            phoneBase.getServiceStateTracker().registerForDataConnectionAttached(mRspHandler,
                EVENT_DATA_ATTACHED + index, null);
            phoneBase.getServiceStateTracker().registerForDataConnectionDetached(mRspHandler,
                EVENT_DATA_DETACHED + index, null);
            //M: Register for Combine attach
            phoneBase.mCi.registerSetDataAllowed(mRspHandler, EVENT_SET_DATA_ALLOWED + index, null);
        }
        // }@
        phoneBase.mCi.registerForSimPlugOut(mRspHandler, EVENT_RESTORE_PENDING + index, null);
        phoneBase.mCi.registerForNotAvailable(mRspHandler, EVENT_RESTORE_PENDING + index, null);
    }

    private Handler mRspHandler = new Handler() {
        @Override
        public void handleMessage(Message msg){
            // MTK
            // NOTE: the order IS important!
            if (msg.what >= EVENT_RESTORE_PENDING) {
                logd("EVENT_SIM" + (msg.what - EVENT_RESTORE_PENDING + 1) + "_RESTORE.");
                restorePendingRequest(msg.what - EVENT_RESTORE_PENDING);
            } else if (msg.what >= EVENT_SET_DATA_ALLOWED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_SET_DATA_ALLOWED + 1) + "_SET_DATA_ALLOWED");
                transitToAttachingState(msg.what - EVENT_SET_DATA_ALLOWED);
            } else
            // CM
            if (msg.what >= EVENT_DATA_DETACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_DETACHED + 1)
                        + "_DATA_DETACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_DETACHED].notifyDataDetached();

            } else if (msg.what >= EVENT_DATA_ATTACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_ATTACHED + 1)
                        + "_DATA_ATTACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_ATTACHED].notifyDataAttached();
            }
        }
    };

    public static DctController getInstance() {
       if (sDctController == null) {
        throw new RuntimeException(
            "DctController.getInstance can't be called before makeDCTController()");
        }
       return sDctController;
    }

    public static DctController makeDctController(PhoneProxy[] phones, Looper looper) {
        if (sDctController == null) {
            logd("makeDctController: new DctController phones.length=" + phones.length);
            sDctController = new DctController(phones, looper);
            // MTK doesn't have this
            // DdsScheduler.init();
        }
        logd("makeDctController: X sDctController=" + sDctController);
        return sDctController;
    }

    private DctController(PhoneProxy[] phones, Looper looper) {
        super(looper);
        logd("DctController(): phones.length=" + phones.length);
        if (phones == null || phones.length == 0) {
            if (phones == null) {
                loge("DctController(phones): UNEXPECTED phones=null, ignore");
            } else {
                loge("DctController(phones): UNEXPECTED phones.length=0, ignore");
            }
            return;
        }
        mPhoneNum = phones.length;
        mPhones = phones;

        mDcSwitchStateMachine = new DcSwitchStateMachine[mPhoneNum];
        mDcSwitchAsyncChannel = new DcSwitchAsyncChannel[mPhoneNum];
        mDcSwitchStateHandler = new Handler[mPhoneNum];
        mNetworkFactoryMessenger = new Messenger[mPhoneNum];
        mNetworkFactory = new NetworkFactory[mPhoneNum];
        mNetworkFilter = new NetworkCapabilities[mPhoneNum];

        // MTK
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            mIratDataSwitchHelper = new MdIratDataSwitchHelper[mPhoneNum];
        }

        for (int i = 0; i < mPhoneNum; ++i) {
            int phoneId = i;
            mDcSwitchStateMachine[i] = new DcSwitchStateMachine(mPhones[i],
                    "DcSwitchStateMachine-" + phoneId, phoneId);
            mDcSwitchStateMachine[i].start();
            mDcSwitchAsyncChannel[i] = new DcSwitchAsyncChannel(mDcSwitchStateMachine[i], phoneId);
            mDcSwitchStateHandler[i] = new Handler();

            int status = mDcSwitchAsyncChannel[i].fullyConnectSync(mPhones[i].getContext(),
                mDcSwitchStateHandler[i], mDcSwitchStateMachine[i].getHandler());

            if (status == AsyncChannel.STATUS_SUCCESSFUL) {
                logd("DctController(phones): Connect success: " + i);
            } else {
                loge("DctController(phones): Could not connect to " + i);
            }

            // MTK
            // Register for radio state change
            // M: [C2K][IRAT] Change for phone ID.
            PhoneBase phoneBase = getActivePhone(phoneId);
            updatePhoneBaseForIndex(i, phoneBase);
        }

        mContext = mPhones[0].getContext();

        // MTK
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHUTDOWN_IPO);
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        mContext.registerReceiver(mIntentReceiver, filter);

        HandlerThread t = new HandlerThread("DdsSwitchSerializer");
        t.start();

        mDdsSwitchSerializer = new DdsSwitchSerializerHandler(t.getLooper());

        // mContext.registerReceiver(subInfoBroadcastReceiver,
        //         new IntentFilter(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED));

        // mContext.registerReceiver(defaultDdsBroadcastReceiver,
        //         new IntentFilter(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED));

        mSubMgr = SubscriptionManager.from(mContext);
        // mSubMgr.addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        //Register for settings change.
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION),
                false, mObserver);

        // MTK
        mDataSubSelector = new DataSubSelector(mContext, mPhoneNum);

        //Since the enter of attaching state may get instance of DctController,
        //we need make sure dctController has already been created.
        setAlwaysAttachSim();
    }

    public void dispose() {
        logd("DctController.dispose");
        for (int i = 0; i < mPhoneNum; ++i) {
            ConnectivityManager cm = (ConnectivityManager)mPhones[i].getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkFactory(mNetworkFactoryMessenger[i]);
            mNetworkFactoryMessenger[i] = null;

            // MTK
            // M: Register for Combine attach
            PhoneBase phoneBase = getActivePhone(i);
            phoneBase.mCi.unregisterForSimPlugOut(mRspHandler);
            phoneBase.mCi.unregisterForNotAvailable(mRspHandler);

            /// M: [C2K][IRAT] Unregister for IRAT flow.
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                mIratDataSwitchHelper[i].unregisterForDataConnectionAttached(mRspHandler);
                mIratDataSwitchHelper[i].unregisterForDataConnectionDetached(mRspHandler);
                mIratDataSwitchHelper[i].unregisterSetDataAllowed(mRspHandler);
            } else {
                phoneBase.getServiceStateTracker().unregisterForDataConnectionAttached(mRspHandler);
                phoneBase.getServiceStateTracker().unregisterForDataConnectionDetached(mRspHandler);
                phoneBase.mCi.unregisterSetDataAllowed(mRspHandler);
            }
        }
        releaseAllNetworkRequests();

        // mContext.unregisterReceiver(defaultDdsBroadcastReceiver);
        // mContext.unregisterReceiver(subInfoBroadcastReceiver);

        // mSubMgr.removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void handleMessage (Message msg) {
        logd("handleMessage msg=" + msg);
        boolean isLegacySetDds = false;
        switch (msg.what) {
            case EVENT_LEGACY_SET_DATA_SUBSCRIPTION:
                isLegacySetDds = true;
                    //intentional fall through, no break.
            case EVENT_ALL_DATA_DISCONNECTED: {
                AsyncResult ar = (AsyncResult)msg.obj;
                SwitchInfo s = (SwitchInfo)ar.userObj;
                Integer phoneId = s.mPhoneId;
                Rlog.d(LOG_TAG, "EVENT_ALL_DATA_DISCONNECTED switchInfo :" + s +
                        " isLegacySetDds = " + isLegacySetDds);
                // In this case prefPhoneId points to the newDds we are trying to
                // set, hence we do not need to call unregister for data disconnected
                if (!isLegacySetDds) {
                    int prefPhoneId = mSubController.getPhoneId(
                             mSubController.getCurrentDds());
                    mPhones[prefPhoneId].unregisterForAllDataDisconnected(this);
                }
                if (phoneId >= 0) {
                    Message allowedDataDone = Message.obtain(this,
                            EVENT_SET_DATA_ALLOW_DONE, s);
                    Phone phone = mPhones[phoneId].getActivePhone();

                    if (!isOnDemandDdsSwitchInProgress) {
                        informDefaultDdsToPropServ(phoneId);
                    } else {
                        int defPhoneId = getDataConnectionFromSetting();
                        informDefaultDdsToPropServ(defPhoneId);
                        isOnDemandDdsSwitchInProgress = false;
                    }
                    DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
                    dcTracker.setDataAllowed(true, allowedDataDone);
                }
               break;
            }

            case EVENT_DELAYED_RETRY: {
                Rlog.d(LOG_TAG, "EVENT_DELAYED_RETRY");
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    SwitchInfo s = (SwitchInfo)args.arg1;
                    boolean psAttach = (boolean)args.arg2;
                    Rlog.d(LOG_TAG, " Retry, switchInfo = " + s);

                    Integer phoneId = s.mPhoneId;
                    int[] subId = mSubController.getSubId(phoneId);
                    Phone phone = mPhones[phoneId].getActivePhone();
                    DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;

                    if(psAttach) {
                        Message psAttachDone = Message.obtain(this,
                                EVENT_SET_DATA_ALLOW_DONE, s);
                        dcTracker.setDataAllowed(true, psAttachDone);
                    } else {
                        Message psDetachDone = Message.obtain(this,
                                EVENT_SET_DATA_ALLOW_FALSE, s);
                        dcTracker.setDataAllowed(false, psDetachDone);
                    }
                } finally {
                    args.recycle();
                }
                break;
            }

            case EVENT_SET_DATA_ALLOW_DONE: {
                AsyncResult ar = (AsyncResult)msg.obj;
                SwitchInfo s = (SwitchInfo)ar.userObj;

                Exception errorEx = null;

                Integer phoneId = s.mPhoneId;
                int[] subId = mSubController.getSubId(phoneId);
                Rlog.d(LOG_TAG, "EVENT_SET_DATA_ALLOWED_DONE  phoneId :" + subId[0]
                        + ", switchInfo = " + s);

                if (ar.exception != null) {
                    Rlog.d(LOG_TAG, "Failed, switchInfo = " + s
                            + " attempt delayed retry");
                    s.incRetryCount();
                    if ( s.isRetryPossible()) {
                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = s;
                        args.arg2 = true;
                        sendMessageDelayed(obtainMessage(EVENT_DELAYED_RETRY, args),
                                ATTACH_RETRY_DELAY);
                        return;
                    } else {
                        Rlog.d(LOG_TAG, "Already did max retries, notify failure");
                        errorEx = new RuntimeException("PS ATTACH failed");
                   }
                } else {
                    Rlog.d(LOG_TAG, "PS ATTACH success = " + s);
                }

                mDdsSwitchSerializer.unLock();

                if (s.mIsDefaultDataSwitchRequested) {
                    mNotifyDefaultDataSwitchInfo.notifyRegistrants(
                            new AsyncResult(null, subId[0], errorEx));
                } else if (s.mIsOnDemandPsAttachRequested) {
                    mNotifyOnDemandPsAttach.notifyRegistrants(
                            new AsyncResult(null, s.mNetworkRequest, errorEx));
                } else {
                    mNotifyOnDemandDataSwitchInfo.notifyRegistrants(
                            new AsyncResult(null, s.mNetworkRequest, errorEx));
                }
                break;
            }

            case EVENT_SET_DATA_ALLOW_FALSE: {
                AsyncResult ar = (AsyncResult)msg.obj;
                SwitchInfo s = (SwitchInfo)ar.userObj;

                Exception errorEx = null;

                Integer phoneId = s.mPhoneId;
                int[] subId = mSubController.getSubId(phoneId);
                Rlog.d(LOG_TAG, "EVENT_SET_DATA_FALSE  phoneId :" + subId[0]
                        + ", switchInfo = " + s);

                if (ar.exception != null) {
                    Rlog.d(LOG_TAG, "Failed, switchInfo = " + s
                            + " attempt delayed retry");
                    s.incRetryCount();
                    if (s.isRetryPossible()) {
                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = s;
                        args.arg2 = false;
                        sendMessageDelayed(obtainMessage(EVENT_DELAYED_RETRY, args),
                                ATTACH_RETRY_DELAY);
                        return;
                    } else {
                        Rlog.d(LOG_TAG, "Already did max retries, notify failure");
                        errorEx = new RuntimeException("PS DETACH failed");
                        mNotifyOnDemandDataSwitchInfo.notifyRegistrants(
                                new AsyncResult(null, s.mNetworkRequest, errorEx));
                   }
                } else {
                    Rlog.d(LOG_TAG, "PS DETACH success = " + s);
                }
                break;
            }

            case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                if(msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                    logd("HALF_CONNECTED: Connection successful with DDS switch"
                            + " service");
                    mDdsSwitchPropService = (AsyncChannel) msg.obj;
                } else {
                    logd("HALF_CONNECTED: Connection failed with"
                            +" DDS switch service, err = " + msg.arg1);
                }
                   break;
            }

            case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                logd("Connection disconnected with DDS switch service");
                mDdsSwitchPropService = null;
                break;
            }

            case EVENT_PROCESS_REQUESTS:
                onProcessRequest();
                break;
            case EVENT_EXECUTE_REQUEST:
                onExecuteRequest((RequestInfo)msg.obj);
                break;
            case EVENT_EXECUTE_ALL_REQUESTS:
                onExecuteAllRequests(msg.arg1);
                break;
            case EVENT_RELEASE_REQUEST:
                onReleaseRequest((RequestInfo)msg.obj);
                break;
            case EVENT_RELEASE_ALL_REQUESTS:
                onReleaseAllRequests(msg.arg1);
                break;
            default:
                loge("Un-handled message [" + msg.what + "]");
        }
    }

    private int requestNetwork(NetworkRequest request, int priority) {
        logd("requestNetwork request=" + request
                + ", priority=" + priority);

        RequestInfo requestInfo = new RequestInfo(request, priority);
        mRequestInfos.put(request.requestId, requestInfo);
        processRequests();

        return PhoneConstants.APN_REQUEST_STARTED;
    }

    private int releaseNetwork(NetworkRequest request) {
        RequestInfo requestInfo = mRequestInfos.get(request.requestId);
        logd("releaseNetwork request=" + request + ", requestInfo=" + requestInfo);

        mRequestInfos.remove(request.requestId);

        // MTK
        String specifier = request.networkCapabilities.getNetworkSpecifier();
        boolean bToAttachingState = false;
        int phoneId = -1;
        if (specifier != null && !specifier.equals("")) {
            int subId =  Integer.parseInt(specifier);
            if (subId < SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                request.networkCapabilities.
                hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
                bToAttachingState = true;
                phoneId = mSubController.getPhoneId(subId);
            }
        }

        releasePendingRequest(request);
        releaseRequest(requestInfo);
        processRequests();

        // MTK
        if (bToAttachingState) {
            logd("ECC w/o SIM, disconnectAllSync to transit to attaching state: " + bToAttachingState
                 + ", Set phoneId: " + phoneId + " to attaching state");
            mDcSwitchAsyncChannel[phoneId].disconnectAllSync();
        }

        return PhoneConstants.APN_REQUEST_STARTED;
    }

    void processRequests() {
        logd("processRequests");

        // MTK
        // remove redundant messages firstly, this situation happens offen.
        removeMessages(EVENT_PROCESS_REQUESTS);

        sendMessage(obtainMessage(EVENT_PROCESS_REQUESTS));
    }

    void executeRequest(RequestInfo request) {
        logd("executeRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_EXECUTE_REQUEST, request));
    }

    void executeAllRequests(int phoneId) {
        logd("executeAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_EXECUTE_ALL_REQUESTS, phoneId,0));
    }

    void releaseRequest(RequestInfo request) {
        logd("releaseRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_RELEASE_REQUEST, request));
    }

    void releaseAllRequests(int phoneId) {
        logd("releaseAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_RELEASE_ALL_REQUESTS, phoneId, 0));
    }

    private void onProcessRequest() {
        for (int i = 0; i < getGroupNumbers(); i++) {
            onProcessGroup(i);
        }
    }

    // MTK version of onProcessRequest with "group" support
    private void onProcessGroup(int group) {
        //process all requests
        //1. Check all requests and find subscription of the top priority
        //   request
        //2. Is current data allowed on the selected subscription
        //2-1. If yes, execute all the requests of the sub
        //2-2. If no, set data not allow on the current PS subscription
        //2-2-1. Set data allow on the selected subscription

        int phoneId = getTopPriorityRequestPhoneId(group);
        int activePhoneId = -1;

        for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
            if (getGroupId(i) == group && !mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        logd("onProcessGroup phoneId=" + phoneId + ", groupId=" + group
                + ", activePhoneId=" + activePhoneId);

        /** M: handle data not allowed that all state should be set to IDLD */
        if (mDataAllowed) {
            if (activePhoneId == -1 || activePhoneId == phoneId) {
                Iterator<Integer> iterator = mRequestInfos.keySet().iterator();

                if (activePhoneId == -1 && !iterator.hasNext()) {
                    logd("No active phone, set phone" + phoneId + " to attaching state");
                    transitToAttachingState(phoneId);
                }

                while (iterator.hasNext()) {
                    RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                    if (getRequestPhoneId(requestInfo.request) == phoneId
                            && requestInfo.mGId == group
                            && !requestInfo.executed) {
                        mDcSwitchAsyncChannel[phoneId].connectSync(requestInfo);
                    }
                }
            } else {
                mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
            }
        } else {
            if (activePhoneId != -1) {
                logd("onProcessRequest data is not allowed, release all requests");
                onReleaseAllRequests(activePhoneId);
            } else {
                logd("onProcessRequest data is not allowed and already in IDLE state");
            }
        }
    }

    private void onExecuteRequest(RequestInfo requestInfo) {
        logd("onExecuteRequest request=" + requestInfo);

        // MTK: Fix a timing issue if we already restore the request to pending queue.
        // Check the request which we want to execute is still in the requestInfo or not.
        // if (!requestInfo.executed) {
        if (needExecuteRequest(requestInfo)) {
            requestInfo.executed = true;
            String apn = apnForNetworkRequest(requestInfo.request);
            int phoneId = getRequestPhoneId(requestInfo.request);

            // MTK
            logd("onExecuteRequest apn = " + apn + " phoneId=" + phoneId);
            requestInfo.phoneId = phoneId; // remember the executed phone id.

            PhoneBase phoneBase = (PhoneBase)mPhones[phoneId].getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.incApnRefCount(apn);
        }
    }

    private void onExecuteAllRequests(int phoneId) {
        logd("onExecuteAllRequests phoneId=" + phoneId + /* MTK */ ",request size = " + mRequestInfos.size());
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            if (getRequestPhoneId(requestInfo.request) == phoneId) {
                onExecuteRequest(requestInfo);
            }
        }
    }

    private void onReleaseRequest(RequestInfo requestInfo) {
        logd("onReleaseRequest request=" + requestInfo);
        if (requestInfo != null && requestInfo.executed) {
            String apn = apnForNetworkRequest(requestInfo.request);
            // MTK
            // int phoneId = getRequestPhoneId(requestInfo.request);
            int phoneId = requestInfo.phoneId;
            PhoneBase phoneBase = (PhoneBase)mPhones[phoneId].getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.decApnRefCount(apn);
            requestInfo.executed = false;
        }
    }

    private void onReleaseAllRequests(int phoneId) {
        logd("onReleaseAllRequests phoneId=" + phoneId);
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            // MTK
            // if (getRequestPhoneId(requestInfo.request) == phoneId) {
            if (requestInfo.phoneId == phoneId) {
                onReleaseRequest(requestInfo);
            }
        }
    }

    private void onSettingsChange() {
        //Sub Selection
        int dataSubId = mSubController.getDefaultDataSubId();

        // MTK
        /** M: Set data ICCID for combination attach */
        int dataPhoneId = SubscriptionManager.getPhoneId(dataSubId);
        String defaultIccid = "";
        if (dataPhoneId >= 0) {
            if (dataPhoneId >= PROPERTY_ICCID_SIM.length) {
                loge("onSettingsChange, phoneId out of boundary:" + dataPhoneId);
            } else {
                defaultIccid = SystemProperties.get(PROPERTY_ICCID_SIM[dataPhoneId]);
                logd("onSettingsChange, Iccid = " + defaultIccid + ", dataPhoneId:" + dataPhoneId);
                if (defaultIccid.equals("")) {
                    logd("onSettingsChange, get iccid fail");
                    SystemProperties.set(PROPERTY_RIL_DATA_ICCID, defaultIccid);
                    return;
                }
            }
        } else {
            logd("onSettingsChange, default data unset");
        }
        SystemProperties.set(PROPERTY_RIL_DATA_ICCID, defaultIccid);

        // MTK
        /*
        int activePhoneId = -1;
        for (int i=0; i<mDcSwitchStateMachine.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        int[] subIds = SubscriptionManager.getSubId(activePhoneId);
        if (subIds ==  null || subIds.length == 0) {
            loge("onSettingsChange, subIds null or length 0 for activePhoneId " + activePhoneId);
            return;
        }

        logd("onSettingsChange, data sub: " + dataSubId + ", active data sub: " + subIds[0]);
        */

        logd("onSettingsChange, data sub: " + dataSubId
                + ", defaultIccid: " + defaultIccid);

        // if (subIds[0] != dataSubId) {
            int i = 0;  // MTK
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                String specifier = requestInfo.request.networkCapabilities.getNetworkSpecifier();
                String apn = apnForNetworkRequest(requestInfo.request);  // MTK
                if (specifier == null || specifier.equals("")) {
                    if (requestInfo.executed && /* MTK */ !apn.equals(PhoneConstants.APN_TYPE_IMS)) {
                        /*
                        String apn = apnForNetworkRequest(requestInfo.request);
                        logd("[setDataSubId] activePhoneId:" + activePhoneId + ", subId =" +
                                dataSubId);
                        PhoneBase phoneBase =
                                (PhoneBase)mPhones[activePhoneId].getActivePhone();
                        DcTrackerBase dcTracker = phoneBase.mDcTracker;
                        dcTracker.decApnRefCount(apn);
                        requestInfo.executed = false;
                        */
                        onReleaseRequest(requestInfo);
                    }

                    for (i = 0; i < mPhoneNum; ++i) {
                        ((DctController.TelephonyNetworkFactory) mNetworkFactory[i])
                               .addPendingRequest(requestInfo.request);
                    }
                    iterator.remove();
                }
            }
        // }

        // Some request maybe pending due to invalid settings
        // Try to handle pending request when settings changed
        for (/* int */ i = 0; i < mPhoneNum; ++i) {
            ((DctController.TelephonyNetworkFactory)mNetworkFactory[i]).evalPendingRequest();
        }

        processRequests();
    }

    private int getTopPriorityRequestPhoneId(int group) {
        RequestInfo retRequestInfo = null;
        int phoneId = -1;
        int priority = -1;

        // MTK
        /*
        for (RequestInfo requestInfo : mRequestInfos.values()) {
            logd("selectExecPhone requestInfo = " + requestInfo);
            if (priority < requestInfo.priority) {
                priority = requestInfo.priority;
                retRequestInfo = requestInfo;
            }
        }
        */
        //TODO: Handle SIM Switch
        for (int i = 0; i < mPhoneNum; i++) {
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                logd("selectExecPhone requestInfo = " + requestInfo);
                if (getRequestPhoneId(requestInfo.request) == i
                        && priority < requestInfo.priority
                        && requestInfo.mGId == group) {
                    priority = requestInfo.priority;
                    retRequestInfo = requestInfo;
                }
            }
        }

        if (retRequestInfo != null) {
            phoneId = getRequestPhoneId(retRequestInfo.request);
        }
        // MTK
        else {
            phoneId = getPreferPhoneId(group);
        }

        logd("getTopPriorityRequestPhoneId = " + phoneId
                + ", priority = " + priority
                + ", group = " + group);

        return phoneId;
    }

    private class SwitchInfo {
        private int mRetryCount = 0;

        public int mPhoneId;
        public NetworkRequest mNetworkRequest;
        public boolean mIsDefaultDataSwitchRequested;
        public boolean mIsOnDemandPsAttachRequested;

        public SwitchInfo(int phoneId, NetworkRequest n, boolean flag, boolean isAttachReq) {
            mPhoneId = phoneId;
            mNetworkRequest = n;
            mIsDefaultDataSwitchRequested = flag;
            mIsOnDemandPsAttachRequested = isAttachReq;
        }

        public SwitchInfo(int phoneId,boolean flag) {
            mPhoneId = phoneId;
            mNetworkRequest = null;
            mIsDefaultDataSwitchRequested = flag;
        }

        public void incRetryCount() {
            mRetryCount++;

        }

        public boolean isRetryPossible() {
            return (mRetryCount < MAX_RETRY_FOR_ATTACH);
        }

        public String toString() {
            return "SwitchInfo[phoneId = " + mPhoneId
                + ", NetworkRequest =" + mNetworkRequest
                + ", isDefaultSwitchRequested = " + mIsDefaultDataSwitchRequested
                + ", isOnDemandPsAttachRequested = " + mIsOnDemandPsAttachRequested
                + ", RetryCount = " + mRetryCount;
        }
    }

    private void doDetach(int phoneId) {
        Phone phone = mPhones[phoneId].getActivePhone();
        DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
        dcTracker.setDataAllowed(false, null);
        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            //cleanup data from apss as there is no detach procedure for CDMA
            dcTracker.cleanUpAllConnections("DDS switch");
        }
    }

    public void setDefaultDataSubId(int reqSubId) {
        int reqPhoneId = mSubController.getPhoneId(reqSubId);
        int currentDds = mSubController.getCurrentDds();
        int defaultDds = mSubController.getDefaultDataSubId();
        SwitchInfo s = new SwitchInfo(new Integer(reqPhoneId), true);
        int currentDdsPhoneId = mSubController.getPhoneId(currentDds);
        if (currentDdsPhoneId < 0 || currentDdsPhoneId >= mPhoneNum) {
            // If Current dds subId is invalid set the received subId as current DDS
            // This generally happens when device power-up first time.
            logd(" setDefaultDataSubId,  reqSubId = " + reqSubId + " currentDdsPhoneId  "
                    + currentDdsPhoneId);
            mSubController.setDataSubId(reqSubId);
            defaultDds = reqSubId;
            currentDdsPhoneId = mSubController.getPhoneId(defaultDds);
        }
        Rlog.d(LOG_TAG, "setDefaultDataSubId reqSubId :" + reqSubId + " reqPhoneId = "
                + reqPhoneId);

        // Avoid sending data allow false and true on same sub .
        if ((reqSubId != defaultDds) && (reqPhoneId != currentDdsPhoneId)) {
            doDetach(currentDdsPhoneId);
        } else {
            logd("setDefaultDataSubId for default DDS, skip PS detach on DDS subs");
            sendMessage(obtainMessage(EVENT_LEGACY_SET_DATA_SUBSCRIPTION,
                        new AsyncResult(s, null, null)));
            return;
        }

        mPhones[currentDdsPhoneId].registerForAllDataDisconnected(
                this, EVENT_ALL_DATA_DISCONNECTED, s);
    }

    private void informDefaultDdsToPropServ(int defDdsPhoneId) {
        if (mDdsSwitchPropService != null) {
            logd("Inform OemHookDDS service of current DDS = " + defDdsPhoneId);
            mDdsSwitchPropService.sendMessageSynchronously(1, defDdsPhoneId,
                    mPhoneNum);
            logd("OemHookDDS service finished");
        } else {
            logd("OemHookDds service not ready yet");
        }

    }

    public void doPsAttach(NetworkRequest n) {
        Rlog.d(LOG_TAG, "doPsAttach for :" + n);

        int subId = mSubController.getSubIdFromNetworkRequest(n);

        int phoneId = mSubController.getPhoneId(subId);
        Phone phone = mPhones[phoneId].getActivePhone();
        DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;

        //request only PS ATTACH on requested subscription.
        //No DdsSerealization lock required.
        SwitchInfo s = new SwitchInfo(new Integer(phoneId), n, false, true);

        Message psAttachDone = Message.obtain(this,
                EVENT_SET_DATA_ALLOW_DONE, s);

        int defDdsPhoneId = getDataConnectionFromSetting();
        informDefaultDdsToPropServ(defDdsPhoneId);
        dcTracker.setDataAllowed(true, psAttachDone);
    }

    //
    // This is public API and client might call doPsDetach on DDS sub.
    // Ignore if thats the case.
    //
    public void doPsDetach() {
        int currentDds = mSubController.getCurrentDds();
        int defaultDds = mSubController.getDefaultDataSubId();

        if (currentDds == defaultDds) {
            Rlog.d(LOG_TAG, "PS DETACH on DDS sub is not allowed.");
            return;
        }
        Rlog.d(LOG_TAG, "doPsDetach for sub:" + currentDds);

        int phoneId = mSubController.getPhoneId(
                mSubController.getCurrentDds());

        Phone phone = mPhones[phoneId].getActivePhone();
        DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
        dcTracker.setDataAllowed(false, null);
    }

    public void setOnDemandDataSubId(NetworkRequest n) {
        Rlog.d(LOG_TAG, "setDataAllowed for :" + n);
        mDdsSwitchSerializer.sendMessage(mDdsSwitchSerializer
                .obtainMessage(EVENT_START_DDS_SWITCH, n));
    }

    public void registerForDefaultDataSwitchInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mNotifyDefaultDataSwitchInfo) {
            mNotifyDefaultDataSwitchInfo.add(r);
        }
    }

    public void registerForOnDemandDataSwitchInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mNotifyOnDemandDataSwitchInfo) {
            mNotifyOnDemandDataSwitchInfo.add(r);
        }
    }

    public void registerForOnDemandPsAttach(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mNotifyOnDemandPsAttach) {
            mNotifyOnDemandPsAttach.add(r);
        }
    }

    public void registerDdsSwitchPropService(Messenger messenger) {
        logd("Got messenger from DDS switch service, messenger = " + messenger);
        AsyncChannel ac = new AsyncChannel();
        ac.connect(mContext, sDctController, messenger);
    }

    class DdsSwitchSerializerHandler extends Handler {
        final static String TAG = "DdsSwitchSerializer";

        public DdsSwitchSerializerHandler(Looper looper) {
            super(looper);
        }

        public void unLock() {
            Rlog.d(TAG, "unLock the DdsSwitchSerializer");
            synchronized(this) {
                mIsDdsSwitchCompleted = true;
                Rlog.d(TAG, "unLocked the DdsSwitchSerializer");
                notifyAll();
            }

        }

        public boolean isLocked() {
            synchronized(this) {
                Rlog.d(TAG, "isLocked = " + !mIsDdsSwitchCompleted);
                return !mIsDdsSwitchCompleted;
            }

        }

        @Override
        public void handleMessage (Message msg) {
            switch(msg.what) {
                case EVENT_START_DDS_SWITCH: {
                    Rlog.d(TAG, "EVENT_START_DDS_SWITCH");

                    try {
                        synchronized(this) {
                            while(!mIsDdsSwitchCompleted) {
                                Rlog.d(TAG, "DDS switch in progress, wait");
                                wait();
                            }

                            Rlog.d(TAG, "Locked!");
                            mIsDdsSwitchCompleted = false;
                        }
                    } catch (Exception e) {
                        Rlog.d(TAG, "Exception while serializing the DDS"
                                + " switch request , e=" + e);
                        return;
                    }

                    NetworkRequest n = (NetworkRequest)msg.obj;

                    Rlog.d(TAG, "start the DDS switch for req " + n);
                    int subId = mSubController.getSubIdFromNetworkRequest(n);

                    if(subId == mSubController.getCurrentDds()) {
                        Rlog.d(TAG, "No change in DDS, respond back");
                        mIsDdsSwitchCompleted = true;
                        mNotifyOnDemandDataSwitchInfo.notifyRegistrants(
                                new AsyncResult(null, n, null));
                        return;
                    }
                    int phoneId = mSubController.getPhoneId(subId);
                    int prefPhoneId = mSubController.getPhoneId(
                            mSubController.getCurrentDds());
                    Phone phone = mPhones[prefPhoneId].getActivePhone();
                    DcTrackerBase dcTracker =((PhoneBase)phone).mDcTracker;
                    SwitchInfo s = new SwitchInfo(new Integer(phoneId), n, false, false);
                    Message dataAllowFalse = Message.obtain(DctController.this,
                            EVENT_SET_DATA_ALLOW_FALSE, s);
                    dcTracker.setDataAllowed(false, dataAllowFalse);
                    if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
                        //cleanup data from apss as there is no detach procedure for CDMA
                        dcTracker.cleanUpAllConnections("Ondemand DDS switch");
                    }
                    mPhones[prefPhoneId].registerForAllDataDisconnected(
                            sDctController, EVENT_ALL_DATA_DISCONNECTED, s);
                    isOnDemandDdsSwitchInProgress = true;
                    break;
                }
            }
        }
    }

    public boolean isDctControllerLocked() {
        return mDdsSwitchSerializer.isLocked();
    }

    private void onSubInfoReady() {
        logd("onSubInfoReady mPhoneNum=" + mPhoneNum);
        for (int i = 0; i < mPhoneNum; ++i) {
            int subId = mPhones[i].getSubId();
            logd("onSubInfoReady handle pending requests subId=" + subId);
            mNetworkFilter[i].setNetworkSpecifier(String.valueOf(subId));
            ((DctController.TelephonyNetworkFactory)mNetworkFactory[i]).evalPendingRequest();
        }
        processRequests();
    }

    private String apnForNetworkRequest(NetworkRequest nr) {
        NetworkCapabilities nc = nr.networkCapabilities;
        // For now, ignore the bandwidth stuff
        if (nc.getTransportTypes().length > 0 &&
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == false) {
            return null;
        }

        // in the near term just do 1-1 matches.
        // TODO - actually try to match the set of capabilities
        int type = -1;
        String name = null;

        boolean error = false;
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DEFAULT;
            type = ConnectivityManager.TYPE_MOBILE;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_MMS;
            type = ConnectivityManager.TYPE_MOBILE_MMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_SUPL;
            type = ConnectivityManager.TYPE_MOBILE_SUPL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DUN;
            type = ConnectivityManager.TYPE_MOBILE_DUN;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_FOTA;
            type = ConnectivityManager.TYPE_MOBILE_FOTA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IMS;
            type = ConnectivityManager.TYPE_MOBILE_IMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CBS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CBS;
            type = ConnectivityManager.TYPE_MOBILE_CBS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IA;
            type = ConnectivityManager.TYPE_MOBILE_IA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCS)) {
            if (name != null) error = true;
            // MTK
            // name = null;
            // loge("RCS APN type not yet supported");
            name = PhoneConstants.APN_TYPE_RCS;
            type = ConnectivityManager.TYPE_MOBILE_RCS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)) {
            if (name != null) error = true;
            // MTK
            // name = null;
            // loge("XCAP APN type not yet supported");
            name = PhoneConstants.APN_TYPE_XCAP;
            type = ConnectivityManager.TYPE_MOBILE_XCAP;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
            if (name != null) error = true;
            // MTK
            // name = null;
            // loge("EIMS APN type not yet supported");
            name = PhoneConstants.APN_TYPE_EMERGENCY;
            type = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
            logd("### EIMS type tmp support");
        }
        // MTK
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DM)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DM;
            type = ConnectivityManager.TYPE_MOBILE_DM;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_WAP)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_WAP;
            type = ConnectivityManager.TYPE_MOBILE_WAP;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_NET;
            type = ConnectivityManager.TYPE_MOBILE_NET;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CMMAIL;
            type = ConnectivityManager.TYPE_MOBILE_CMMAIL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_TETHERING;
            type = ConnectivityManager.TYPE_MOBILE_TETHERING;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCSE)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_RCSE;
            type = ConnectivityManager.TYPE_MOBILE_RCSE;
        }
        if (error) {
            loge("Multiple apn types specified in request - result is unspecified!");
        }
        if (type == -1 || name == null) {
            loge("Unsupported NetworkRequest in Telephony: nr=" + nr);
            return null;
        }
        return name;
    }

    private int getRequestPhoneId(NetworkRequest networkRequest) {
        String specifier = networkRequest.networkCapabilities.getNetworkSpecifier();
        int subId;
        if (specifier == null || specifier.equals("")) {
            subId = mSubController.getDefaultDataSubId();
        } else {
            subId = Integer.parseInt(specifier);
        }
        int phoneId = mSubController.getPhoneId(subId);
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            phoneId = 0;
            if (!SubscriptionManager.isValidPhoneId(phoneId)) {
                throw new RuntimeException("Should not happen, no valid phoneId");
            }
        }
        // MTK
        // M: [C2K][IRAT] change phone ID for IRAT.
        logd("before mapping, getRequestPhoneId phoneId= " + phoneId);
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            phoneId = SvlteUtils.getSlotId(phoneId);
        }
        logd("getRequestPhoneId phoneId= " + phoneId);
        return phoneId;
    }

    private int getDataConnectionFromSetting(){
        int subId = mSubController.getDefaultDataSubId();
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return phoneId;
    }

    private static void logd(String s) {
        if (DBG) Rlog.d(LOG_TAG, s);
    }

    private static void loge(String s) {
        if (DBG) Rlog.e(LOG_TAG, s);
    }

    private class TelephonyNetworkFactory extends NetworkFactory {
        //Thread safety not required as long as list operation are done by single thread.
        private SparseArray<NetworkRequest> mDdsRequests = new SparseArray<NetworkRequest>();
        private final SparseArray<NetworkRequest> mPendingReq = new SparseArray<NetworkRequest>();
        private Phone mPhone;
        private NetworkCapabilities mNetworkCapabilities;

        // MTK
        /// M:[C2K][IRAT] Add pending release request for IRAT.
        private final SparseArray<NetworkRequest> mPendingReleaseReq =
                new SparseArray<NetworkRequest>();
        private int mGroupId;

        public TelephonyNetworkFactory(Looper l, Context c, String TAG, Phone phone,
                NetworkCapabilities nc) {
            super(l, c, TAG, nc);
            mPhone = phone;
            mNetworkCapabilities = nc;
            log("NetworkCapabilities: " + nc);
            // MTK
            mGroupId = getGroupId(mPhone.getPhoneId());
        }

        public void processPendingNetworkRequests(NetworkRequest n) {
            for (int i = 0; i < mDdsRequests.size(); i++) {
                NetworkRequest nr = mDdsRequests.valueAt(i);
                if (nr.equals(n)) {
                    log("Found pending request in ddsRequest list = " + nr);
                    String apn = apnForNetworkRequest(nr);
                    DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
                    if (dcTracker.isApnSupported(apn)) {
                        dcTracker.incApnRefCount(apn);
                    } else {
                        log("Unsupported APN");
                    }
                }
            }
        }

        private void registerOnDemandDdsCallback() {
            SubscriptionController subController = SubscriptionController.getInstance();

            subController.registerForOnDemandDdsLockNotification(mPhone.getSubId(),
                    new SubscriptionController.OnDemandDdsLockNotifier() {
                        public void notifyOnDemandDdsLockGranted(NetworkRequest n) {
                            log("Got the tempDds lock for the request = " + n);
                            processPendingNetworkRequests(n);
                        }
                    });
        }

        public void updateNetworkCapability() {
            int subId = mPhone.getSubId();
            log("update networkCapabilites for subId = " + subId);

            mNetworkCapabilities.setNetworkSpecifier(""+subId);
            if ((subId > 0 && SubscriptionController.getInstance().
                    getSubState(subId) == SubscriptionManager.ACTIVE) &&
                    (subId == SubscriptionController.getInstance().getDefaultDataSubId())) {
                log("INTERNET capability is with subId = " + subId);
                //Only defaultDataSub provides INTERNET.
                mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                log("INTERNET capability is removed from subId = " + subId);
                mNetworkCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            }
            setScoreFilter(50);
            registerOnDemandDdsCallback();

            log("Ready to handle network requests");
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            // figure out the apn type and enable it
            if (DBG) log("Cellular needs Network for " + networkRequest);

            int subId = mPhone.getSubId();
            // MTK
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                subId = SvlteUtils.getSvlteSubIdBySubId(subId);
            }

            // MTK
            if ((!SubscriptionManager.isUsableSubIdValue(subId))
                    || isNetworkRequestSuspend()) {
                if (networkRequest.networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
                    log("Sub Info has not been ready or during IRAT, but EIMS request, not put into pending request!!");
                } else {
                    log("Sub Info has not been ready or during IRAT, pending request.");
                    mPendingReq.put(networkRequest.requestId, networkRequest);
                    return;
                }
            }

            int requestPhoneId = getRequestPhoneId(networkRequest);
            int currentPhoneId = mPhone.getPhoneId();
            log("[IRAT_DctController] needNetworkFor: requestPhoneId = "
                    + requestPhoneId + ", currentPhoneId = " + currentPhoneId);
            // M: mapping LTE phone id to slot index
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                currentPhoneId = SvlteUtils.getSlotId(currentPhoneId);
            }
            // MTK TODO: the remaining logic is radically different... for now just blindly port everything
            // for fixing build quicker, and try to wire the MTK bits with AOSP/CM logic instead.
            if (requestPhoneId == currentPhoneId) {
                DcTrackerBase dcTracker = ((PhoneBase) mPhone).mDcTracker;
                String apn = apnForNetworkRequest(networkRequest);
                if (dcTracker.isApnSupported(apn)) {
                    requestNetwork(networkRequest, dcTracker.getApnPriority(apn), mGroupId);
                } else {
                    log("Unsupported APN");
                }
            } else {
                log("Request not send, put to pending");
                mPendingReq.put(networkRequest.requestId, networkRequest);
            }
            // MTK XXX: temporarily make the AOSP code unreachable
            return;

            /*
            SubscriptionController subController = SubscriptionController.getInstance();
            log("subController = " + subController);

            int currentDds = subController.getDefaultDataSubId();
            int requestedSpecifier = subController.getSubIdFromNetworkRequest(networkRequest);

            log("CurrentDds = " + currentDds);
            log("mySubId = " + subId);
            log("Requested networkSpecifier = " + requestedSpecifier);
            log("my networkSpecifier = " + mNetworkCapabilities.getNetworkSpecifier());


            if (!SubscriptionManager.isValidSubscriptionId(currentDds)) {
                log("Can't handle any network request now, currentDds not ready.");
                return;
            }

            // For clients that do not send subId in NetworkCapabilities,
            // Connectivity will send to all network factories. Accept only
            // when requestedSpecifier is same as current factory's subId
            if (requestedSpecifier != subId) {
                log("requestedSpecifier is not same as mysubId. Bail out.");
                return;
            }

            if (currentDds != requestedSpecifier) {
                log("This request would result in DDS switch");
                log("Requested DDS switch to subId = " + requestedSpecifier);

                //Queue this request and initiate temp DDS switch.
                //Once the DDS switch is done we will revist the pending requests.
                mDdsRequests.put(networkRequest.requestId, networkRequest);
                requestOnDemandDataSubscriptionLock(networkRequest);

                return;
            } else {
                if(isNetworkRequestForInternet(networkRequest)) {
                    log("Activating internet request on subId = " + subId);
                    String apn = apnForNetworkRequest(networkRequest);
                    DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
                    if (dcTracker.isApnSupported(apn)) {
                        requestNetwork(networkRequest, dcTracker.getApnPriority(apn));
                    } else {
                        log("Unsupported APN");
                    }
                } else {
                    if(isValidRequest(networkRequest)) {
                        //non-default APN requests for this subscription.
                        mDdsRequests.put(networkRequest.requestId, networkRequest);
                        requestOnDemandDataSubscriptionLock(networkRequest);
                    } else {
                        log("Bogus request req = " + networkRequest);
                    }
                }
            }
            */
        }

        public void releasePendingRequest(NetworkRequest networkRequest) {
            log("releasePendingRequest " + networkRequest);
            mPendingReq.remove(networkRequest.requestId);
        }

        private boolean isValidRequest(NetworkRequest n) {
            int[] types = n.networkCapabilities.getCapabilities();
            return (types.length > 0);
        }

        private boolean isNetworkRequestForInternet(NetworkRequest n) {
            boolean flag = n.networkCapabilities.hasCapability
                (NetworkCapabilities.NET_CAPABILITY_INTERNET);
            log("Is the request for Internet = " + flag);
            return flag;
        }

        private void requestOnDemandDataSubscriptionLock(NetworkRequest n) {
            if(!isNetworkRequestForInternet(n)) {
                //Request tempDDS lock only for non-default PDP requests
                SubscriptionController subController = SubscriptionController.getInstance();
                log("requestOnDemandDataSubscriptionLock for request = " + n);
                subController.startOnDemandDataSubscriptionRequest(n);
            }
        }

        private void removeRequestFromList(SparseArray<NetworkRequest> list, NetworkRequest n) {
            NetworkRequest nr = list.get(n.requestId);
            if (nr != null) {
                log("Removing request = " + nr);
                list.remove(n.requestId);
                String apn = apnForNetworkRequest(nr);
                DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
                if (dcTracker.isApnSupported(apn)) {
                    dcTracker.decApnRefCount(apn);
                } else {
                    log("Unsupported APN");
                }
            }
        }

        private void removeRequestIfFound(NetworkRequest n) {
            log("Release the request from dds queue, if found");
            removeRequestFromList(mDdsRequests, n);

            if(!isNetworkRequestForInternet(n)) {
                SubscriptionController subController = SubscriptionController.getInstance();
                subController.stopOnDemandDataSubscriptionRequest(n);
            } else {
                // Internet requests are not queued in DDS list. So deactivate here explicitly.
                String apn = apnForNetworkRequest(n);
                DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
                if (dcTracker.isApnSupported(apn)) {
                    dcTracker.decApnRefCount(apn);
                } else {
                    log("Unsupported APN");
                }
            }
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            log("Cellular releasing Network for " + networkRequest);
            //M: Fix google Issue
            //if (!SubscriptionManager.isUsableSubIdValue(mPhone.getSubId())) {
            if (mPendingReq.get(networkRequest.requestId) != null ) {
                log("Sub Info has not been ready, remove request.");
                mPendingReq.remove(networkRequest.requestId);
                return;
            }

            // M:[C2K][IRAT] Pending release request during IRAT.
            if (isNetworkRequestSuspend()) {
                mPendingReleaseReq.put(networkRequest.requestId, networkRequest);
                log("Pending release request during IRAT: request = "
                        + networkRequest);
                return;
            }

            // M: [C2K][IRAT] Mapping phone ID for SVLTE during release
            int phoneId = mPhone.getPhoneId();
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                phoneId = SvlteUtils.getSlotId(phoneId);
                logd("[IRAT_DctController] releaseNetworkFor: IRAT change phone ID:" + phoneId);
            }

            if (getRequestPhoneId(networkRequest) == phoneId) {
                DcTrackerBase dcTracker = ((PhoneBase) mPhone).mDcTracker;
                String apn = apnForNetworkRequest(networkRequest);
                if (dcTracker.isApnSupported(apn)) {
                    releaseNetwork(networkRequest);
                } else {
                    log("Unsupported APN");
                }

            } else {
                log("Request not release");
            }
            // MTK XXX: temporarily make the AOSP code unreachable
            return;

            /*
            if (!SubscriptionManager.isUsableSubIdValue(mPhone.getSubId())) {
                log("Sub Info has not been ready, remove request.");
                mPendingReq.remove(networkRequest.requestId);
                return;
            }

            NetworkRequest nr = mDdsRequests.get(networkRequest.requestId);
            if (nr != null) {
                removeRequestIfFound(networkRequest);
            } else {
                if (getRequestPhoneId(networkRequest) == mPhone.getPhoneId()) {
                    DcTrackerBase dcTracker =((PhoneBase)mPhone).mDcTracker;
                    String apn = apnForNetworkRequest(networkRequest);
                    if (dcTracker.isApnSupported(apn)) {
                        releaseNetwork(networkRequest);
                    } else {
                        log("Unsupported APN");
                    }

                } else {
                    log("Request not release");
                }
            }
            */
        }

        public void releaseAllNetworkRequests() {
            log("releaseAllNetworkRequests");
            SubscriptionController subController = SubscriptionController.getInstance();
            for (int i = 0; i < mDdsRequests.size(); i++) {
                NetworkRequest nr = mDdsRequests.valueAt(i);
                if (nr != null) {
                    log("Removing request = " + nr);
                    subController.stopOnDemandDataSubscriptionRequest(nr);
                    mDdsRequests.remove(nr.requestId);
                }
            }
        }

        @Override
        protected void log(String s) {
            if (DBG) Rlog.d(LOG_TAG, "[TNF " + mPhone.getSubId() + "]" + s);
        }

        // MTK
        public void addPendingRequest(NetworkRequest networkRequest) {
            log("addPendingRequest, request:" + networkRequest);
            mPendingReq.put(networkRequest.requestId, networkRequest);
        }

        public void evalPendingRequest() {
            log("evalPendingRequest, pending request size is " + mPendingReq.size());
            int key = 0;
            for(int i = 0; i < mPendingReq.size(); i++) {
                key = mPendingReq.keyAt(i);
                NetworkRequest request = mPendingReq.get(key);
                log("evalPendingRequest: request = " + request);

                mPendingReq.remove(request.requestId);
                needNetworkFor(request, 0);
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("DctController:");
        try {
            for (DcSwitchStateMachine dssm : mDcSwitchStateMachine) {
                dssm.dump(fd, pw, args);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            for (Entry<Integer, RequestInfo> entry : mRequestInfos.entrySet()) {
                pw.println("mRequestInfos[" + entry.getKey() + "]=" + entry.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
    }

    // MTK

    private boolean isValidPhoneId(int phoneId) {
        return phoneId >= 0 && phoneId <= mPhoneNum;
    }

    private int requestNetwork(NetworkRequest request, int priority, int gid) {
        logd("requestNetwork request=" + request + ", priority=" + priority
            + ", gid = " + gid);

        RequestInfo requestInfo = new RequestInfo(request, priority, gid);
        mRequestInfos.put(request.requestId, requestInfo);
        processRequests();

        return PhoneConstants.APN_REQUEST_STARTED;
    }

    /** M: try to do re-attach
     *  Disconnect all data connections and do detach if current state is ATTACHING or ATTACHED
     *  Once detach is done, all requests would be processed again when entering IDLE state
     *  That means re-attach will be triggered
     */
    void disconnectAll() {
        int activePhoneId = -1;
        for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        if (activePhoneId >= 0) {
            logd("disconnectAll, active phone:" + activePhoneId);
            mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
        } else {
            logd("disconnectAll but no active phone, process requests");
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logd("onReceive: action=" + action);
            /* if (action.equals(ACTION_SHUTDOWN_IPO)) {
                logd("IPO Shutdown, clear PROPERTY_DATA_ALLOW_SIM, PROPERTY_IA_APN_SET_ICCID");
                logd("IPO Shutdown, clear TEMP_IA");
                SystemProperties.set(PROPERTY_DATA_ALLOW_SIM, "");
                SystemProperties.set(PROPERTY_IA_APN_SET_ICCID, "");
                SystemProperties.set(PROPERTY_TEMP_IA, "");
                SystemProperties.set(PROPERTY_TEMP_IA_APN, "");
            } else */
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                onSubInfoReady();
            }
        }
    };

    /** M: get acive data phone Id */
    public int getActiveDataPhoneId() {
        int activePhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }
        return activePhoneId;
    }

    /** M: allow data service or not and can set a max timeout for setting data not allowed */
    public void setDataAllowed(long subId, boolean allowed, String reason, long timeout) {
        logd("setDataAllowed subId=" + subId + ", allowed=" + allowed + ", reason="
            + reason + ", timeout=" + timeout);
        mDataAllowed = allowed;
        if (mDataAllowed) {
            mRspHandler.removeCallbacks(mDataNotAllowedTimeoutRunnable);
        }

        setDataAllowedReasonToDct(reason);
        processRequests();

        if (!mDataAllowed && timeout > 0) {
            logd("start not allow data timer and timeout=" + timeout);
            mRspHandler.postDelayed(mDataNotAllowedTimeoutRunnable, timeout);
        }
    }

    private void setDataAllowedReasonToDct(String reason) {
        logd("setDataAllowedReasonToDct reason: " + reason);
        for (int i = 0; i < mPhoneNum; i++) {
            PhoneBase phoneBase = getActivePhone(i);
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.mSetDataAllowedReason = reason;
        }
    }

    public synchronized void registerForDcSwitchStateChange(Handler h, int what,
            Object obj, DcStateParam param) {

        Registrant r = new Registrant(h, what, obj);
        DcStateParam dcState = null;

        if (param == null) {
            dcState = new DcStateParam("Don't care", false);
        } else {
            dcState = param;
        }

        if (DBG) {
            logd("registerForDcSwitchStateChange: dcState = " + dcState);
        }

        dcState.mRegistrant = r;
        mDcSwitchStateChange.put(h, dcState);
    }

    public synchronized void unregisterForDcSwitchStateChange(Handler h) {
        if (DBG) {
            logd("unregisterForDcSwitchStateChange");
        }
        mDcSwitchStateChange.remove(h);
    }

    synchronized void notifyDcSwitchStateChange(String state, int phoneId, String reason) {
        mUserCnt = 0;
        mTransactionId++;

        for (DcStateParam param : mDcSwitchStateChange.values()) {
            String user = param.mUser;
            Registrant r = param.mRegistrant;
            Message msg = null;

            if (state.equals(DcSwitchStateMachine.DCSTATE_PREDETACH_CHECK) && param.mNeedCheck) {
                msg = obtainMessage(EVENT_CONFIRM_PREDETACH, mTransactionId, phoneId, user);
                mUserCnt++;
            }

            DcStateParam dcState = new DcStateParam(state, phoneId, reason, msg);
            AsyncResult ar = new AsyncResult(null, dcState, null);
            r.notifyRegistrant(ar);
        }

        if (DBG) {
            logd("notifyDcSwitchStateChange: user:" + mUserCnt + ", ID:" + mTransactionId);
        }

        if (state.equals(DcSwitchStateMachine.DCSTATE_PREDETACH_CHECK) && mUserCnt == 0) {
            obtainMessage(EVENT_CONFIRM_PREDETACH, mTransactionId, phoneId,
                    "No User").sendToTarget();
        }
    }

    private void handleConfirmPreDetach(Message msg) {
        int transAct = msg.arg1;
        int phoneId = msg.arg2;
        String user = (String) msg.obj;

        if (mTransactionId != transAct) {
            if (DBG) {
                logd("unExcept transAct: " + transAct);
            }
        }

        if (mUserCnt > 0) {
            mUserCnt--;
        }

        if (DBG) {
            logd("handleConfirmPreDetach: user:" + user + ", ID:" + transAct + ", phone" + phoneId
                + ", Remain User:" + mUserCnt);
        }

        if (mUserCnt == 0) {
            mDcSwitchAsyncChannel[phoneId].confirmPreDetachSync();
        }
    }

    private void setAlwaysAttachSim() {
        MultiSimVariants config = TelephonyManager.getDefault().getMultiSimConfiguration();
        // We divide phones into different groups according to multi sim config.
        if (config == MultiSimVariants.DSDS
                || CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                || config == MultiSimVariants.TSTS) {
            String attachPhone = "";
            attachPhone = SystemProperties.get(PROPERTY_DATA_ALLOW_SIM, "");
            logd(" attachPhone: " + attachPhone);
            if (attachPhone != null && !attachPhone.equals("")) {
                int phoneId = Integer.parseInt(attachPhone);
                if (phoneId >= 0 && phoneId < mPhoneNum) {
                    logd("Set phone" + phoneId + " to attaching state");
                    sendMessage(obtainMessage(EVENT_TRANSIT_TO_ATTACHING, phoneId, 0));
                }
            }
        } else if (config == MultiSimVariants.DSDA) {
            //TODO: Extend to nSmA
            //FIXME: Need to get total group numbers
            for (int i = 0; i < mPhoneNum; i++) {
                sendMessage(obtainMessage(EVENT_TRANSIT_TO_ATTACHING, i, 0));
            }
        }
    }

    /** M: transit to attaching state. */
    private void transitToAttachingState(int targetPhoneId) {
        int groupId = getGroupId(targetPhoneId);
        int topPriorityPhoneId = getTopPriorityRequestPhoneId(groupId);
        int activePhoneId = -1;
        if (topPriorityPhoneId == targetPhoneId
            || (CdmaFeatureOptionUtils.isCdmaLteDcSupport() && mRequestInfos.isEmpty())) {
            for (int i = 0; i < mDcSwitchStateMachine.length; i++) {
                if (!mDcSwitchAsyncChannel[i].isIdleSync() && groupId == getGroupId(i)) {
                    activePhoneId = i;
                    break;
                }
            }
            if (activePhoneId != -1 && activePhoneId != targetPhoneId) {
                logd("transitToAttachingState: disconnect other phone");
                mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
            } else {
                logd("transitToAttachingState: connect");
                mDcSwitchAsyncChannel[targetPhoneId].connectSync(null);
            }
        } else {
            logd("transitToAttachingState: disconnect target phone");
            mDcSwitchAsyncChannel[targetPhoneId].connectSync(null);
            mDcSwitchAsyncChannel[targetPhoneId].disconnectAllSync();
        }
    }

    protected ConcurrentHashMap<String, ApnContext> getApnContexts(int phoneId) {
        PhoneBase phoneBase = getActivePhone(phoneId);
        DcTrackerBase dcTracker = phoneBase.mDcTracker;
        ConcurrentHashMap<String, ApnContext> apnContexts = null;
        if (dcTracker != null) {
            apnContexts = dcTracker.getApnContexts();
        } else {
            loge("DcTracker is null");
        }
        return apnContexts;
    }

    private void restorePendingRequest(int phoneId) {
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            logd("restorePendingRequest requestInfo = " + requestInfo);
            if (getRequestPhoneId(requestInfo.request) == phoneId) {
                ((DctController.TelephonyNetworkFactory) mNetworkFactory[phoneId])
                        .addPendingRequest(requestInfo.request);
                onReleaseRequest(requestInfo);
                iterator.remove();
            }
        }
    }

    private boolean needExecuteRequest(RequestInfo requestInfo) {
        RequestInfo checkInfo = mRequestInfos.get(requestInfo.request.requestId);
        boolean ret = false;

        if (!requestInfo.executed && checkInfo != null) {
            ret = true;
        }

        logd("needExecuteRequest return " + ret + ", checkInfo = " + checkInfo);
        
        return ret;
    }

    public boolean isActivePhone(int phoneId) {
        /// M: [C2K][IRAT] use IRAT support slot ID instead of LTE_SUB_ID.
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            phoneId = SvlteUtils.getSvltePhoneIdByPhoneId(phoneId);
        }
        return !mDcSwitchAsyncChannel[phoneId].isIdleSync();
    }

    public class DcStateParam {
        private String mState;
        private int mPhoneId;
        private Message mMessage;

        private String mUser;
        private boolean mNeedCheck;
        private Registrant mRegistrant;
        private String mReason;

        public DcStateParam(String state, int phoneId, String reason, Message msg) {
            mState = state;
            mPhoneId = phoneId;
            mReason = reason;
            mMessage = msg;
        }

        public DcStateParam(String user, boolean needCheckDisconnect) {
            mUser = user;
            mNeedCheck = needCheckDisconnect;
        }

        public String getState() {
            return mState;
        }

        public int getPhoneId() {
            return mPhoneId;
        }

        public String getReason() {
            return mReason;
        }
        public boolean confirmPreCheckDetach() {
            logd("confirmPreCheckDetach, msg = " + mMessage);
            if (mMessage == null) {
                return false;
            } else {
                mMessage.sendToTarget();
                return true;
            }
        }

        @Override
        public String toString() {
            return "[ mState=" + mState + ", mReason=" + mReason + ", mPhoneId =" + mPhoneId
                    + ", user = " + mUser + ", needCheck = " + mNeedCheck
                    + ", message = " + mMessage + ", Registrant = " + mRegistrant + "]";
        }
    }

    //Multi-Group START
    private int getGroupId(int phoneId) {
        MultiSimVariants config = TelephonyManager.getDefault().getMultiSimConfiguration();
        int groupId = 0;

        // We divide phones into different groups according to multi sim config.
        // M: [C2K] [IRAT] set group id to 0 for all phones in SVLTE.
        if (!CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                && config == MultiSimVariants.DSDA) {
            groupId = phoneId;
        }

        logd("getGroupId phone = " + phoneId + ", groupId = "  + groupId);
        return groupId;
    }

    private int getGroupNumbers() {
        MultiSimVariants config = TelephonyManager.getDefault().getMultiSimConfiguration();
        int groupNumber = 1;

        //TODO: Enhance to nSmA
        if (!CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                && config == MultiSimVariants.DSDA) {
            groupNumber = mPhoneNum;
        }

        logd("getGroupNumbers groupNumber = " + groupNumber);
        return groupNumber;
    }

    private int getPreferPhoneId(int groupId) {

        //TODO: Enhance to nSmA
        int dataPhoneId = getDefaultDataPhoneId();
        int attachPhoneId = getAttachPropertyPhone();
        if (dataPhoneId >= 0 && dataPhoneId < mPhoneNum && getGroupId(dataPhoneId) == groupId) {
            logd("getPreferPhoneId: return default data phone Id = " + dataPhoneId);
            return dataPhoneId;
        } else if (attachPhoneId >= 0 && attachPhoneId < mPhoneNum
                && getGroupId(dataPhoneId) == groupId) {
            logd("get attach phone Id = " + attachPhoneId);
            return attachPhoneId;
        } else {
            String curr3GSim = SystemProperties.get("persist.radio.simswitch", "");
            int curr3GPhoneId = -1;
            logd("current 3G Sim = " + curr3GSim);
            if (curr3GSim != null && !curr3GSim.equals("")) {
                curr3GPhoneId = Integer.parseInt(curr3GSim) - 1;
            }
            if (curr3GPhoneId != -1 && getGroupId(curr3GPhoneId) == groupId) {
                logd("getPreferPhoneId return current 3G SIM: " + curr3GSim);
                return curr3GPhoneId;
            }
        }
        logd("getPreferPhoneId: no prefer phone found, return default value: " + groupId);
        return groupId;
    }
    //Multi-Group END

    private int getDefaultDataPhoneId() {
        int dataPhoneId = mSubController.getPhoneId(mSubController.getDefaultDataSubId());
        String dataIccid = "";
        String simIccid = "";
        if (dataPhoneId < 0 || dataPhoneId > mPhoneNum) {
            logd("getDefaultDataPhoneId: invalid phone ID " + dataPhoneId + " ,find property");
            dataIccid = SystemProperties.get(PROPERTY_RIL_DATA_ICCID);
            if (dataIccid != null && !dataIccid.equals("")) {
                for (int i = 0; i < mPhoneNum; i++) {
                    simIccid = SystemProperties.get(PROPERTY_ICCID_SIM[i]);
                    if (simIccid != null && dataIccid.equals(simIccid)) {
                        logd("getDefaultDataPhoneId: Sim" + i + " iccid matched: " + simIccid);
                        dataPhoneId = i;
                        break;
                    }
                }
            }
        }
        logd("getDefaultDataPhoneId: dataPhoneId = " + dataPhoneId);

        return dataPhoneId;
    }

    private int getAttachPropertyPhone() {
        //Since the enter of attaching state may get instance of DctController,
        //we need make sure dctController has already been created.
        String attachPhone = "";
        int phoneId = -1;
        attachPhone = SystemProperties.get(PROPERTY_DATA_ALLOW_SIM, "");
        logd("attachPhone: " + attachPhone);
        if (attachPhone != null && !attachPhone.equals("")) {
            phoneId = Integer.parseInt(attachPhone);
        }

        logd("getAttachPropertyPhone: " + phoneId);

        return phoneId;
    }

    // M: [C2K][IRAT] code start @{
    /**
     * return the active concrete phone object which data is used.
     * @param phoneId the index of SvltePhoneProxy array
     * @return SVLTE mode will return recorded data phone, CSFB just the one.
     */
    private PhoneBase getActivePhone(int phoneId) {
        PhoneBase psPhone = null;
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport() && SvlteUtils.isActiveSvlteMode(phoneId)) {
            psPhone = (PhoneBase) ((SvltePhoneProxy) mPhones[phoneId]).getPsPhone();
        } else {
            psPhone = (PhoneBase) ((PhoneProxy) mPhones[phoneId]).getActivePhone();
        }
        return psPhone;
    }

    private boolean isNetworkRequestSuspend() {
        return mSuspendNetworkRequest;
    }

    /**
     * Suspend network request and data switch request.
     */
    public void suspendNetworkRequest() {
        logd("[IRAT_DctController] suspendNetworkRequest: mSuspendNetworkRequest = "
                + mSuspendNetworkRequest);
        mSuspendNetworkRequest = true;
    }

    /**
     * Resume network request, called in IRAT finished.
     */
    public void resumeNetworkRequest() {
        logd("[IRAT_DctController] resumeNetworkRequest: mSuspendNetworkRequest = "
                + mSuspendNetworkRequest);
        mSuspendNetworkRequest = false;

        for (int i = 0; i < mPhoneNum; ++i) {
            ((DctController.TelephonyNetworkFactory) mNetworkFactory[i]).evalPendingRequest();
        }

        if (mHasPendingDataSwitch) {
            onSettingsChange();
            mHasPendingDataSwitch = false;
        }
    }
    //M: [C2K][IRAT] end }@
}
