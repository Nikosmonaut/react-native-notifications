package com.wix.reactnativenotifications.core.notification;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.facebook.common.executors.UiThreadImmediateExecutorService;
import com.facebook.common.references.CloseableReference;
import com.facebook.datasource.DataSource;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.react.bridge.ReactContext;
import com.wix.reactnativenotifications.core.AppLaunchHelper;
import com.wix.reactnativenotifications.core.AppLifecycleFacade;
import com.wix.reactnativenotifications.core.AppLifecycleFacade.AppVisibilityListener;
import com.wix.reactnativenotifications.core.AppLifecycleFacadeHolder;
import com.wix.reactnativenotifications.core.InitialNotificationHolder;
import com.wix.reactnativenotifications.core.JsIOHelper;
import com.wix.reactnativenotifications.core.NotificationIntentAdapter;
import com.wix.reactnativenotifications.core.ProxyService;

import static com.wix.reactnativenotifications.Defs.NOTIFICATION_OPENED_EVENT_NAME;
import static com.wix.reactnativenotifications.Defs.NOTIFICATION_RECEIVED_EVENT_NAME;

public class PushNotification implements IPushNotification {

    final protected Context mContext;
    final protected AppLifecycleFacade mAppLifecycleFacade;
    final protected AppLaunchHelper mAppLaunchHelper;
    final protected JsIOHelper mJsIOHelper;
    final protected PushNotificationProps mNotificationProps;
    final protected AppVisibilityListener mAppVisibilityListener = new AppVisibilityListener() {
        @Override
        public void onAppVisible() {
            mAppLifecycleFacade.removeVisibilityListener(this);
            dispatchImmediately();
        }

        @Override
        public void onAppNotVisible() {
        }
    };

    public static IPushNotification get(Context context, Bundle bundle) {
        Context appContext = context.getApplicationContext();
        if (appContext instanceof INotificationsApplication) {
            return ((INotificationsApplication) appContext).getPushNotification(context, bundle, AppLifecycleFacadeHolder.get(), new AppLaunchHelper());
        }
        return new PushNotification(context, bundle, AppLifecycleFacadeHolder.get(), new AppLaunchHelper(), new JsIOHelper());
    }

    protected PushNotification(Context context, Bundle bundle, AppLifecycleFacade appLifecycleFacade, AppLaunchHelper appLaunchHelper, JsIOHelper JsIOHelper) {
        mContext = context;
        mAppLifecycleFacade = appLifecycleFacade;
        mAppLaunchHelper = appLaunchHelper;
        mJsIOHelper = JsIOHelper;
        mNotificationProps = createProps(bundle);
    }

    @Override
    public void onReceived() throws InvalidNotificationException {
        if (!mAppLifecycleFacade.isAppVisible()) {
            postNotification();
        }
        notifyReceivedToJS();
    }

    @Override
    public void onOpened() {
        digestNotification();
        clearAllNotifications();
    }

    @Override
    public void onPostRequest(Integer notificationId) {
        postNotification();
    }

    @Override
    public PushNotificationProps asProps() {
        return mNotificationProps.copy();
    }

    protected void postNotification() {
        final PendingIntent pendingIntent = getCTAPendingIntent();
        final Notification.Builder notification = getNotificationBuilder(pendingIntent);

        // It should be refactored into a separate method with a callback.
        // But the author if this code does not now for now how to do it in java
        ImageRequest imageRequest = ImageRequest.fromUri(mNotificationProps.getIconUrl());
        ImagePipeline imagePipeline = Fresco.getImagePipeline();
        DataSource<CloseableReference<CloseableImage>> dataSource = imagePipeline.fetchDecodedImage(imageRequest, null);

        dataSource.subscribe(
                new BaseBitmapDataSubscriber() {
                    @Override
                    protected void onNewResultImpl(Bitmap bitmap) {
                        Log.d("MON_TAG", mNotificationProps.getIconUrl());
                        Log.d("MON_TAG", "not error");

                        final Notification.Builder notification = getNotificationBuilder(pendingIntent);

                        notification.setLargeIcon(bitmap);

                        postNotification(notification.build(), null);
                    }

                    @Override
                    protected void onFailureImpl(DataSource<CloseableReference<CloseableImage>> dataSource) {
                        Log.d("MON_TAG", mNotificationProps.getIconUrl());
                        Log.d("MON_TAG", "error");
                        final Notification notification = getNotificationBuilder(pendingIntent).build();

                        postNotification(notification, null);
                    }
                },
                UiThreadImmediateExecutorService.getInstance()
        );
    }

    protected void digestNotification() {
        if (!mAppLifecycleFacade.isReactInitialized()) {
            setAsInitialNotification();
            launchOrResumeApp();
            return;
        }

        final ReactContext reactContext = mAppLifecycleFacade.getRunningReactContext();
        if (reactContext.getCurrentActivity() == null) {
            setAsInitialNotification();
        }

        if (mAppLifecycleFacade.isAppVisible()) {
            dispatchImmediately();
        } else {
            dispatchUponVisibility();
        }
    }

    protected PushNotificationProps createProps(Bundle bundle) {
        return new PushNotificationProps(bundle);
    }

    protected void setAsInitialNotification() {
        InitialNotificationHolder.getInstance().set(mNotificationProps);
    }

    protected void dispatchImmediately() {
        notifyOpenedToJS();
    }

    protected void dispatchUponVisibility() {
        mAppLifecycleFacade.addVisibilityListener(getIntermediateAppVisibilityListener());

        // Make the app visible so that we'll dispatch the notification opening when visibility changes to 'true' (see
        // above listener registration).
        launchOrResumeApp();
    }

    protected AppVisibilityListener getIntermediateAppVisibilityListener() {
        return mAppVisibilityListener;
    }

    protected PendingIntent getCTAPendingIntent() {
        final Intent cta = new Intent(mContext, ProxyService.class);
        return NotificationIntentAdapter.createPendingNotificationIntent(mContext, cta, mNotificationProps);
    }

    protected Notification.Builder getNotificationBuilder(PendingIntent intent) {
        final Notification.Builder notification = new Notification.Builder(mContext)
                .setContentTitle(mNotificationProps.getTitle())
                .setContentIntent(intent)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true);

        setUpIcon(notification);
        setUpChannel(notification);
        setUpBody(notification);

        return notification;
    }

    private void setUpBody(Notification.Builder notification) {
        Resources resources = mContext.getResources();
        String action = mNotificationProps.getAction();
        String body = mNotificationProps.getBody();

        if (body == null && action.equals("followed")) {
            int bodyResourceId = getAppResourceId("notifications_followed", "string");
            body = resources.getString(bodyResourceId);
        } else if (body == null && action.equals("commented")) {
            int bodyResourceId = getAppResourceId("notifications_commented", "string");
            body = resources.getString(bodyResourceId);
        }

        notification.setContentText(body);
    }

    private void setUpChannel(Notification.Builder notification) {
        Resources resources = mContext.getResources();
        String action = mNotificationProps.getAction();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int channelResourceId = getAppResourceId("channel_default_id", "string");
            String channelId = resources.getString(channelResourceId);

            if (action == "followed") {
                channelResourceId = getAppResourceId("channel_followed_id", "string");
                channelId = resources.getString(channelResourceId);
            } else if (action == "commented") {
                channelResourceId = getAppResourceId("channel_comments_id", "string");
                channelId = resources.getString(channelResourceId);
            }
            notification.setChannelId(channelId);
        }
    }

    private void setUpIcon(Notification.Builder notification) {
        int iconResId = getAppResourceId("ic_notification", "drawable");
        if (iconResId != 0) {
            notification.setSmallIcon(iconResId);
        } else {
            notification.setSmallIcon(mContext.getApplicationInfo().icon);
        }

        setUpIconColor(notification);
    }

    private void setUpIconColor(Notification.Builder notification) {
        int colorResID = getAppResourceId("appMain", "color");
        if (colorResID != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int color = mContext.getResources().getColor(colorResID);
            notification.setColor(color);
        }
    }

    protected int postNotification(Notification notification, Integer notificationId) {
        int id = notificationId != null ? notificationId : createNotificationId(notification);
        postNotification(id, notification);
        return id;
    }

    protected void postNotification(int id, Notification notification) {
        final NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(id, notification);
    }

    protected void clearAllNotifications() {
        final NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    protected int createNotificationId(Notification notification) {
        return (int) System.nanoTime();
    }

    private void notifyReceivedToJS() {
        mJsIOHelper.sendEventToJS(NOTIFICATION_RECEIVED_EVENT_NAME, mNotificationProps.asBundle(), mAppLifecycleFacade.getRunningReactContext());
    }

    private void notifyOpenedToJS() {
        Bundle response = new Bundle();
        response.putBundle("notification", mNotificationProps.asBundle());

        mJsIOHelper.sendEventToJS(NOTIFICATION_OPENED_EVENT_NAME, response, mAppLifecycleFacade.getRunningReactContext());
    }

    protected void launchOrResumeApp() {
        final Intent intent = mAppLaunchHelper.getLaunchIntent(mContext);
        mContext.startActivity(intent);
    }

    private int getAppResourceId(String resName, String resType) {
        return mContext.getResources().getIdentifier(resName, resType, mContext.getPackageName());
    }
}
