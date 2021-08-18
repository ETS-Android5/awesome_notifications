package me.carda.awesome_notifications.background;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import android.content.res.AssetManager;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.FlutterCallbackInformation;

import me.carda.awesome_notifications.Definitions;
import me.carda.awesome_notifications.AwesomeNotificationsPlugin;
import me.carda.awesome_notifications.notifications.NotificationBuilder;
import me.carda.awesome_notifications.notifications.enumerators.NotificationSource;
import me.carda.awesome_notifications.notifications.models.NotificationModel;
import me.carda.awesome_notifications.notifications.models.returnedData.ActionReceived;
import me.carda.awesome_notifications.utils.DateUtils;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.System.exit;

/**
 * An background execution abstraction which handles initializing a background isolate running a
 * callback dispatcher, used to invoke Dart callbacks while backgrounded.
 */
public class DartBackgroundExecutor implements MethodCallHandler {
    private static final String TAG = "DartBackgroundExec";

    public static final NotificationBuilder notificationBuilder = new NotificationBuilder();
    private static final BlockingQueue<Intent> silentDataQueue = new LinkedBlockingDeque<Intent>();

    public static Context applicationContext;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private MethodChannel backgroundChannel;
    private FlutterEngine backgroundFlutterEngine;

    private final long dartCallbackHandle;
    private final long silentCallbackHandle;

    private static DartBackgroundExecutor runningInstance;
    private Runnable dartBgRunnable;
    private Handler handler;

    public static void runBackgroundExecutor(
        Context context,
        Intent silentIntent,
        long dartCallbackHandle,
        long silentCallbackHandle
    ){
        addSilentIntent(silentIntent);
        if (runningInstance == null) {
            runningInstance = new DartBackgroundExecutor(
                    dartCallbackHandle,
                    silentCallbackHandle
            );
            runningInstance.startExecute(context);
        }
    }

    public DartBackgroundExecutor(long dartCallbackHandle, long silentCallbackHandle){
        this.dartCallbackHandle = dartCallbackHandle;
        this.silentCallbackHandle = silentCallbackHandle;
    }

    public void startExecute(Context context) {
        if (isNotRunning()) {
            isRunning.set(true);
            applicationContext = context;

            if (dartCallbackHandle != 0) {
                runBackgroundThread(dartCallbackHandle);
            }
        }
    }

    public boolean isNotRunning() {
        return !isRunning.get();
    }

    private static void addSilentIntent(Intent intent){
        silentDataQueue.add(intent);
    }

    @Override
    public void onMethodCall(MethodCall call, @NonNull Result result) {
        String method = call.method;
        try {
            if (method.equals(Definitions.CHANNEL_METHOD_INITIALIZE)) {
                dischargeNextSilentExecution();
                result.success(true);
            } else {
                result.notImplemented();
            }
        } catch (Exception e) {
            result.error("error", "Dart background error: " + e.getMessage(), null);
        }
    }

    public void runBackgroundThread(final long callbackHandle) {

        if (backgroundFlutterEngine != null) {
            Log.e(TAG, "Background isolate already started.");
            return;
        }

        dartBgRunnable = new Runnable() {
            @Override
            public void run() {

                backgroundFlutterEngine = new FlutterEngine(applicationContext);

                FlutterInjector flutterInjector = FlutterInjector.instance();
                FlutterLoader loader = flutterInjector.flutterLoader();


                if (!loader.initialized()) {
                    loader.startInitialization(applicationContext);
                }

                loader.ensureInitializationComplete(
                    applicationContext,
                    null
                );

                FlutterCallbackInformation flutterCallback =
                        FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);

                if(flutterCallback == null){
                    closeBackgroundIsolate();
                    return;
                }

                String appBundlePath = loader.findAppBundlePath();
                AssetManager assets = applicationContext.getAssets();
                DartExecutor executor = backgroundFlutterEngine.getDartExecutor();

                initializeReverseMethodChannel(executor);

                DartCallback dartCallback =
                        new DartCallback(assets, appBundlePath, flutterCallback);
                executor.executeDartCallback(dartCallback);
            }
        };

        // TODO run dart code in background thread, instead of the main one
        /* HandlerThread bgThread = new HandlerThread("BackgroundSilentThread");
        bgThread.start();
        handler = new Handler(bgThread.getLooper());*/
//
        handler = new Handler(Looper.getMainLooper());
        handler.post(dartBgRunnable);
    }

    private void initializeReverseMethodChannel(BinaryMessenger isolate) {
        backgroundChannel = new MethodChannel(isolate, Definitions.DART_REVERSE_CHANNEL);
        backgroundChannel.setMethodCallHandler(this);
    }

    public void closeBackgroundIsolate() {
        if (!isNotRunning()) {
            isRunning.set(false);

            if(backgroundFlutterEngine != null){
                backgroundFlutterEngine.destroy();
                backgroundFlutterEngine = null;
            }

            if(handler != null){
                handler.removeCallbacks(dartBgRunnable);
                handler = null;
            }

            runningInstance = null;
        }

        exit(0);
    }

    public void dischargeNextSilentExecution(){
        if (!silentDataQueue.isEmpty()) {
            try {
                Intent intent = silentDataQueue.take();
                executeDartCallbackInBackgroundIsolate(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void finishDartBackgroundExecution(){
        if(silentDataQueue.isEmpty()) {
            if(AwesomeNotificationsPlugin.debug)
                Log.i(TAG, "All silent data fetched.");
            closeBackgroundIsolate();
        }
        else {
            if (AwesomeNotificationsPlugin.debug)
                Log.i(TAG, "Remaining " + silentDataQueue.size() + " silents to finish");
            dischargeNextSilentExecution();
        }
    }

    private final Result dartChannelResultHandle =
        new Result() {
            @Override
            public void success(Object result) {
                finishDartBackgroundExecution();
            }

            @Override
            public void error(String errorCode, String errorMessage, Object errorDetails) {
                finishDartBackgroundExecution();
            }

            @Override
            public void notImplemented() {
                finishDartBackgroundExecution();
            }
        };

    public void executeDartCallbackInBackgroundIsolate(Intent intent) {

        if (backgroundFlutterEngine == null) {
            Log.i( TAG,"A background message could not be handled since " +
                    "dart callback handler has not been registered.");
            return;
        }

        // Handle the message event in Dart.
        NotificationModel notificationModel = NotificationBuilder.buildNotificationModelFromIntent(intent);
        if (notificationModel != null) {
            ActionReceived actionReceived = NotificationBuilder.buildNotificationActionFromNotificationModel(applicationContext, notificationModel, intent);

            // If silent request does not came from an action button
            if(actionReceived == null){
                actionReceived = new ActionReceived(notificationModel.content);
            }

            actionReceived.setActualActionAttributes();

            if(actionReceived.displayedDate == null){
                actionReceived.displayedDate = actionReceived.createdDate;
                actionReceived.displayedLifeCycle = actionReceived.createdLifeCycle;
            }

            final Map<String, Object> actionData = actionReceived.toMap();

            backgroundChannel.invokeMethod(
                    Definitions.CHANNEL_METHOD_SILENCED_CALLBACK,
                new HashMap<String, Object>() {
                    {
                        put(Definitions.ACTION_HANDLE, silentCallbackHandle);
                        put(Definitions.NOTIFICATION_RECEIVED_ACTION, actionData);
                    }
                },
                dartChannelResultHandle);

        } else {
            Log.e(TAG, "Awesome Notification model not found in Intent background.");
            finishDartBackgroundExecution();
        }
    }
}