package com.adobe.phonegap.push

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput
import org.json.JSONException
import org.json.JSONObject
import java.util.*


/**
 * Push Handler Activity
 */
@Suppress("HardCodedStringLiteral")
@SuppressLint("LongLogTag", "LogConditional")
class PushHandlerActivity : Activity() {
  companion object {
    private const val TAG: String = "${PushPlugin.PREFIX_TAG} (PushHandlerActivity)"
  }

  var originalExtras: Bundle? = null

  /**
   * this activity will be started if the user touches a notification that we own.
   * We send it's data off to the push plugin for processing.
   * If needed, we boot up the main activity to kickstart the application.
   *
   * @param savedInstanceState
   *
   * @see android.app.Activity#onCreate(android.os.Bundle)
   */
  public override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.v(TAG, "onCreate")

    val fcm = FCMService()

    intent.extras?.let { extras ->
      val notId = extras.getInt(PushConstants.NOT_ID, 0)
      val callback = extras.getString(PushConstants.CALLBACK)
      var foreground = extras.getBoolean(PushConstants.FOREGROUND, true)
      val startOnBackground = extras.getBoolean(PushConstants.START_IN_BACKGROUND, false)
      val dismissed = extras.getBoolean(PushConstants.DISMISSED, false)

      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

      if(notId == PushConstants.GROUP_NOTIFICATION_ID) {

        // Notification grouper.

        // Since the notification grouper was pressed, we need to remove the items that were grouped and clean the list
        // of the received notifications to be able to display the next grouper correctly once a new items are received.
        fcm.cleanCoresNotificationIDList()
        fcm.cleanNotificationList()

        // Remove the grouper from the Notification Drawer.
        notificationManager.cancel(PushConstants.GROUP_NOTIFICATION_ID)

      } else {

        // Single notification.

        // Remove from the Notification drawer the pressed notification.
        notificationManager.cancel(notId)

        // Remove the content of the pressed notification from the list of received notifications.
        fcm.setNotification(notId, "")

        // Remove from the list that represents an item within CORES Mobile app the item that was pressed.
        try {
          val coresPayloadJsonString =
            intent.extras!!.getBundle(PushConstants.PUSH_BUNDLE)!!["coresPayload"].toString()
          val jsonCoresPayload = JSONObject(coresPayloadJsonString)
          fcm.removeCoresNotificationIDfromList(jsonCoresPayload.getString("notification_id"))
        } catch (e: JSONException) {
          e.printStackTrace()
        }

        // Check if the notification grouper has enough items to be displayed.
        if (fcm.getCoresNotificationIDlist()!!.size === 0) {

          // Remove the notification grouper from the Notification Drawer.
          notificationManager.cancel(PushConstants.GROUP_NOTIFICATION_ID)
        }

      }

      if (!startOnBackground) {
        notificationManager.cancel(FCMService.getAppName(this), notId)
      }

      val notHaveInlineReply = processPushBundle()

      if (notHaveInlineReply && Build.VERSION.SDK_INT < Build.VERSION_CODES.N && !startOnBackground) {
        foreground = true
      }

      Log.d(TAG, "Not ID: $notId")
      Log.d(TAG, "Callback: $callback")
      Log.d(TAG, "Foreground: $foreground")
      Log.d(TAG, "Start On Background: $startOnBackground")
      Log.d(TAG, "Dismissed: $dismissed")

      finish()

      if (!dismissed) {
        Log.d(TAG, "Is Push Plugin Active: ${PushPlugin.isActive}")

        if (!PushPlugin.isActive && foreground && notHaveInlineReply) {
          Log.d(TAG, "Force Main Activity Reload: Start on Background = False")
          forceMainActivityReload(false)
        } else if (startOnBackground) {
          Log.d(TAG, "Force Main Activity Reload: Start on Background = True")
          forceMainActivityReload(true)
        } else {
          Log.d(TAG, "Don't Want Main Activity")
        }

        // Check if the plugin is ready to be used to send the data to the CORES Mobile app.
        if (originalExtras != null) {
          if (PushPlugin.isActive) {
            PushPlugin.sendExtras(originalExtras)
          } else {

            // Create a timer to check once the plugin is available.
            val checkPluginIsReady = Timer()

            // For security reasons, we create a counter to prevent the timer from running indefinitely, running for a maximum of 5 seconds.
            val maxTimerIterations = 25
            val currentTimerIteration = intArrayOf(1)
            checkPluginIsReady.scheduleAtFixedRate(object : TimerTask() {
              override fun run() {
                Log.i("CORES Workflows", "Cordova Plugin Push is not ready, trying again.")
                if (PushPlugin.isActive && currentTimerIteration[0] >= maxTimerIterations) {
                  checkPluginIsReady.cancel()

                  // The Cordova plugin is ready.
                  PushPlugin.sendExtras(originalExtras)
                }
                currentTimerIteration[0] += 1
              }
            }, 0, 200)
          }
        }

      }
    }
  }

  private fun processPushBundle(): Boolean {
    /*
     * Takes the pushBundle extras from the intent,
     * and sends it through to the PushPlugin for processing.
     */
    return intent.extras?.let { extras ->
      var notHaveInlineReply = true

      extras.getBundle(PushConstants.PUSH_BUNDLE)?.apply {
        putBoolean(PushConstants.FOREGROUND, false)
        putBoolean(PushConstants.TAPPED, true);
        putBoolean(PushConstants.COLDSTART, !PushPlugin.isActive)
        putBoolean(PushConstants.DISMISSED, extras.getBoolean(PushConstants.DISMISSED))
        putString(
          PushConstants.ACTION_CALLBACK,
          extras.getString(PushConstants.CALLBACK)
        )
        var notificationsToOpen: String? = ""
        if (extras.getString(PushConstants.BUNDLE_KEY_OPEN_ALL_NOTIFICATIONS) != null) {
          notificationsToOpen = extras.getString(PushConstants.BUNDLE_KEY_OPEN_ALL_NOTIFICATIONS)
        }
        putString(PushConstants.BUNDLE_KEY_OPEN_ALL_NOTIFICATIONS, notificationsToOpen)
        remove(PushConstants.NO_CACHE)

        RemoteInput.getResultsFromIntent(intent)?.let { results ->
          val reply = results.getCharSequence(PushConstants.INLINE_REPLY).toString()
          Log.d(TAG, "Inline Reply: $reply")

          putString(PushConstants.INLINE_REPLY, reply)
          notHaveInlineReply = false
        }

      }

      originalExtras =  extras.getBundle(PushConstants.PUSH_BUNDLE)

      return notHaveInlineReply
    } ?: true
  }

  private fun forceMainActivityReload(startOnBackground: Boolean) {
    /*
     * Forces the main activity to re-launch if it's unloaded.
     */
    val launchIntent = packageManager.getLaunchIntentForPackage(applicationContext.packageName)

    intent.extras?.let { extras ->
      launchIntent?.apply {
        extras.getBundle(PushConstants.PUSH_BUNDLE)?.apply {
          putExtras(this)
        }

        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        addFlags(Intent.FLAG_FROM_BACKGROUND)
        putExtra(PushConstants.START_IN_BACKGROUND, startOnBackground)
      }
    }

    startActivity(launchIntent)
  }

  /**
   * On Resuming of Activity
   */
  override fun onResume() {
    super.onResume()

    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancelAll()
  }
}
