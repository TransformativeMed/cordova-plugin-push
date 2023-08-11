package com.adobe.phonegap.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Html
import android.text.Spanned
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.adobe.phonegap.push.PushPlugin.Companion.isActive
import com.adobe.phonegap.push.PushPlugin.Companion.isInForeground
import com.adobe.phonegap.push.PushPlugin.Companion.sendExtras
import com.adobe.phonegap.push.PushPlugin.Companion.setApplicationIconBadgeNumber
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.transformativemed.coresmobile.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.*
import java.util.stream.Collectors


/**
 * Firebase Cloud Messaging Service Class
 */
@Suppress("HardCodedStringLiteral")
@SuppressLint("NewApi", "LongLogTag", "LogConditional")
class FCMService : FirebaseMessagingService() {
  companion object {
    private const val TAG = "${PushPlugin.PREFIX_TAG} (FCMService)"

    private var messageMap = HashMap<Int, ArrayList<String?>>()
    // Array that will store the ID's of the notifications received. Each ID will be an unique value that represents an item in the CORES Mobile app.
    private var coresNotificationIDlist = ArrayList<String?>()
    private var channelID: String? = null

    private val FLAG_MUTABLE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_MUTABLE
    } else {
      0
    }
    private val FLAG_IMMUTABLE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_IMMUTABLE
    } else {
      0
    }

    /**
     * Get the Application Name from Label
     */
    fun getAppName(context: Context): String {
      return context.packageManager.getApplicationLabel(context.applicationInfo) as String
    }
  }

  private val context: Context
    get() = applicationContext

  private val pushSharedPref: SharedPreferences
    get() = context.getSharedPreferences(
      PushConstants.COM_ADOBE_PHONEGAP_PUSH,
      MODE_PRIVATE
    )

  /**
   * Called when a new token is generated, after app install or token changes.
   *
   * @param token
   */
  override fun onNewToken(token: String) {
    super.onNewToken(token)
    Log.d(TAG, "Refreshed token: $token")

    // TODO: Implement this method to send any registration to your app's servers.
    //sendRegistrationToServer(token);
  }

  /**
   * Set Notification
   * If message is empty or null, the message list is cleared.
   *
   * @param notId
   * @param message
   */
  fun setNotification(notId: Int, message: String?) {
    var messageList = messageMap[notId]

    if (messageList == null) {
      messageList = ArrayList()
      messageMap[notId] = messageList
    }

    if (message == null || message.isEmpty()) {
      messageList.clear()
    } else {
      messageList.add(message)
    }
  }

  /**
   * Function to get the list of the received notifications and the content of each of them .
   */
  fun getMessageMap(): HashMap<Int, ArrayList<String?>> {
    return messageMap
  }

  /**
   * Function to add to a list the ID of the push notification received. This ID represents an item within CORES Mobile app.
   */
  fun setCoresNotificationIDTolist(coresNotificationID: String?) {
    if (coresNotificationIDlist.size === 0) {
      coresNotificationIDlist.add(coresNotificationID!!)
    } else {
      val existsNotificationID = coresNotificationIDlist.contains(coresNotificationID)
      if (!existsNotificationID) {
        coresNotificationIDlist.add(coresNotificationID!!)
      }
    }
  }

  /**
   * Function that returns the list of IDs of the received notifications. Each ID represents an item within the CORES Mobile app.
   */
  fun getCoresNotificationIDlist(): ArrayList<String?>? {
    return coresNotificationIDlist
  }

  /**
   * Function to reset the list of IDs of the received notifications. This list stores the IDs used in the CORES Mobile app.
   */
  fun cleanCoresNotificationIDList() {
    coresNotificationIDlist =  ArrayList<String?>()
  }

  /**
   * Function to re-initialize the list that stores the content of the received notifications.
   */
  fun cleanNotificationList() {
    messageMap = HashMap<Int, ArrayList<String?>>()
  }

  /**
   * Function used to remove an ID from the list of the received notifications. This list stores the IDs of each notification that represents an item in the CORES Mobile app.
   */
  fun removeCoresNotificationIDfromList(coresNotificationID: String?) {
    val indexInList = coresNotificationIDlist.indexOf(coresNotificationID)
    if (indexInList != -1) {
      coresNotificationIDlist.removeAt(indexInList)
    }
  }

  /**
   * On Message Received
   */
  override fun onMessageReceived(message: RemoteMessage) {
    val from = message.from
    Log.d(TAG, "onMessageReceived (from=$from)")

    var extras = Bundle()

    message.notification?.let {
      extras.putString(PushConstants.TITLE, it.title)
      extras.putString(PushConstants.MESSAGE, it.body)
      extras.putString(PushConstants.SOUND, it.sound)
      extras.putString(PushConstants.ICON, it.icon)
      extras.putString(PushConstants.COLOR, it.color)
    }

    for ((key, value) in message.data) {
      extras.putString(key, value)
    }

    if (isAvailableSender(from)) {
      val messageKey = pushSharedPref.getString(PushConstants.MESSAGE_KEY, PushConstants.MESSAGE)
      val titleKey = pushSharedPref.getString(PushConstants.TITLE_KEY, PushConstants.TITLE)

      extras = normalizeExtras(extras, messageKey, titleKey)

      // Clear Badge
      val clearBadge = pushSharedPref.getBoolean(PushConstants.CLEAR_BADGE, false)
      if (clearBadge) {
        setApplicationIconBadgeNumber(context, 0)
      }

      // Foreground
      extras.putBoolean(PushConstants.FOREGROUND, isInForeground)

      // if we are in the foreground and forceShow is `false` only send data.
      // By default when a notification is received, its data should include flags indicating whether the app is in the foreground and whether it was pressed.
      val forceShow = pushSharedPref.getBoolean(PushConstants.FORCE_SHOW, false)
      if (!forceShow && isInForeground) {
        Log.d(TAG, "Do Not Force & Is In Foreground")
        extras.putBoolean(PushConstants.COLDSTART, false)
        extras.putBoolean(PushConstants.FOREGROUND, true)
        extras.putBoolean(PushConstants.TAPPED, false);
        sendExtras(extras)
      } else if (forceShow && isInForeground) {
        Log.d(TAG, "Force & Is In Foreground")
        extras.putBoolean(PushConstants.COLDSTART, false)
        extras.putBoolean(PushConstants.FOREGROUND, true);
        extras.putBoolean(PushConstants.TAPPED, false);
        showNotificationIfPossible(extras)
      } else {
        Log.d(TAG, "In Background")
        extras.putBoolean(PushConstants.COLDSTART, isActive)
        extras.putBoolean(PushConstants.FOREGROUND, false);
        extras.putBoolean(PushConstants.TAPPED, false);
        showNotificationIfPossible(extras)
      }
    }
  }

  private fun replaceKey(oldKey: String, newKey: String, extras: Bundle, newExtras: Bundle) {
    /*
     * Change a values key in the extras bundle
     */
    var value = extras[oldKey]
    if (value != null) {
      when (value) {
        is String -> {
          value = localizeKey(newKey, value)
          newExtras.putString(newKey, value as String?)
        }

        is Boolean -> newExtras.putBoolean(newKey, (value as Boolean?) ?: return)

        is Number -> {
          newExtras.putDouble(newKey, value.toDouble())
        }

        else -> {
          newExtras.putString(newKey, value.toString())
        }
      }
    }
  }

  private fun localizeKey(key: String, value: String): String {
    /*
     * Normalize localization for key
     */
    return when (key) {
      PushConstants.TITLE,
      PushConstants.MESSAGE,
      PushConstants.SUMMARY_TEXT,
      -> {
        try {
          val localeObject = JSONObject(value)
          val localeKey = localeObject.getString(PushConstants.LOC_KEY)
          val localeFormatData = ArrayList<String>()

          if (!localeObject.isNull(PushConstants.LOC_DATA)) {
            val localeData = localeObject.getString(PushConstants.LOC_DATA)
            val localeDataArray = JSONArray(localeData)

            for (i in 0 until localeDataArray.length()) {
              localeFormatData.add(localeDataArray.getString(i))
            }
          }

          val resourceId = context.resources.getIdentifier(
            localeKey,
            "string",
            context.packageName
          )

          if (resourceId != 0) {
            context.resources.getString(resourceId, *localeFormatData.toTypedArray())
          } else {
            Log.d(TAG, "Can't Find Locale Resource (key=$localeKey)")
            value
          }
        } catch (e: JSONException) {
          Log.d(TAG, "No Locale Found (key= $key, error=${e.message})")
          value
        }
      }
      else -> value
    }
  }

  private fun normalizeKey(
    key: String,
    messageKey: String?,
    titleKey: String?,
    newExtras: Bundle,
  ): String {
    /*
     * Replace alternate keys with our canonical value
     */
    return when {
      key == PushConstants.BODY
        || key == PushConstants.ALERT
        || key == PushConstants.MP_MESSAGE
        || key == PushConstants.GCM_NOTIFICATION_BODY
        || key == PushConstants.TWILIO_BODY
        || key == messageKey
        || key == PushConstants.AWS_PINPOINT_BODY
      -> {
        PushConstants.MESSAGE
      }

      key == PushConstants.TWILIO_TITLE || key == PushConstants.SUBJECT || key == titleKey -> {
        PushConstants.TITLE
      }

      key == PushConstants.MSGCNT || key == PushConstants.BADGE -> {
        PushConstants.COUNT
      }

      key == PushConstants.SOUNDNAME || key == PushConstants.TWILIO_SOUND -> {
        PushConstants.SOUND
      }

      key == PushConstants.AWS_PINPOINT_PICTURE -> {
        newExtras.putString(PushConstants.STYLE, PushConstants.STYLE_PICTURE)
        PushConstants.PICTURE
      }

      key.startsWith(PushConstants.GCM_NOTIFICATION) -> {
        key.substring(PushConstants.GCM_NOTIFICATION.length + 1, key.length)
      }

      key.startsWith(PushConstants.GCM_N) -> {
        key.substring(PushConstants.GCM_N.length + 1, key.length)
      }

      key.startsWith(PushConstants.UA_PREFIX) -> {
        key.substring(PushConstants.UA_PREFIX.length + 1, key.length).lowercase()
      }

      key.startsWith(PushConstants.AWS_PINPOINT_PREFIX) -> {
        key.substring(PushConstants.AWS_PINPOINT_PREFIX.length + 1, key.length)
      }

      else -> key
    }
  }

  private fun normalizeExtras(
    extras: Bundle,
    messageKey: String?,
    titleKey: String?,
  ): Bundle {
    /*
     * Parse bundle into normalized keys.
     */
    Log.d(TAG, "normalize extras")

    val it: Iterator<String> = extras.keySet().iterator()
    val newExtras = Bundle()

    while (it.hasNext()) {
      val key = it.next()
      Log.d(TAG, "key = $key")

      // If normalizeKey, the key is "data" or "message" and the value is a json object extract
      // This is to support parse.com and other services. Issue #147 and pull #218
      if (
        key == PushConstants.PARSE_COM_DATA ||
        key == PushConstants.MESSAGE ||
        key == messageKey
      ) {
        val json = extras[key]

        // Make sure data is in json object string format
        if (json is String && json.startsWith("{")) {
          Log.d(TAG, "extracting nested message data from key = $key")

          try {
            // If object contains message keys promote each value to the root of the bundle
            val data = JSONObject(json)
            if (
              data.has(PushConstants.ALERT)
              || data.has(PushConstants.MESSAGE)
              || data.has(PushConstants.BODY)
              || data.has(PushConstants.TITLE)
              || data.has(messageKey)
              || data.has(titleKey)
            ) {
              val jsonKeys = data.keys()

              while (jsonKeys.hasNext()) {
                var jsonKey = jsonKeys.next()
                Log.d(TAG, "key = data/$jsonKey")

                var value = data.getString(jsonKey)
                jsonKey = normalizeKey(jsonKey, messageKey, titleKey, newExtras)
                value = localizeKey(jsonKey, value)
                newExtras.putString(jsonKey, value)
              }
            } else if (data.has(PushConstants.LOC_KEY) || data.has(PushConstants.LOC_DATA)) {
              val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
              Log.d(TAG, "replace key $key with $newKey")
              replaceKey(key, newKey, extras, newExtras)
            }
          } catch (e: JSONException) {
            Log.e(TAG, "normalizeExtras: JSON exception")
          }
        } else {
          val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
          Log.d(TAG, "replace key $key with $newKey")
          replaceKey(key, newKey, extras, newExtras)
        }
      } else if (key == "notification") {
        val value = extras.getBundle(key)
        val iterator: Iterator<String> = value!!.keySet().iterator()

        while (iterator.hasNext()) {
          val notificationKey = iterator.next()
          Log.d(TAG, "notificationKey = $notificationKey")

          val newKey = normalizeKey(notificationKey, messageKey, titleKey, newExtras)
          Log.d(TAG, "Replace key $notificationKey with $newKey")

          var valueData = value.getString(notificationKey)
          valueData = localizeKey(newKey, valueData!!)
          newExtras.putString(newKey, valueData)
        }
        continue
        // In case we weren't working on the payload data node or the notification node,
        // normalize the key.
        // This allows to have "message" as the payload data key without colliding
        // with the other "message" key (holding the body of the payload)
        // See issue #1663
      } else {
        val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
        Log.d(TAG, "replace key $key with $newKey")
        replaceKey(key, newKey, extras, newExtras)
      }
    } // while
    return newExtras
  }

  private fun extractBadgeCount(extras: Bundle?): Int {
    var count = -1

    try {
      extras?.getString(PushConstants.COUNT)?.let {
        count = it.toInt()
      }
    } catch (e: NumberFormatException) {
      Log.e(TAG, e.localizedMessage, e)
    }

    return count
  }

  private fun showNotificationIfPossible(extras: Bundle?) {
    // Send a notification if there is a message or title, otherwise just send data
    extras?.let {
      val message = it.getString(PushConstants.MESSAGE)
      val title = it.getString(PushConstants.TITLE)
      val contentAvailable = it.getString(PushConstants.CONTENT_AVAILABLE)
      val forceStart = it.getString(PushConstants.FORCE_START)
      val badgeCount = extractBadgeCount(extras)
      val tapped = it.getString(PushConstants.TAPPED)

      if (badgeCount >= 0) {
        setApplicationIconBadgeNumber(context, badgeCount)
      }

      if (badgeCount == 0) {
        val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.cancelAll()
      }

      Log.d(TAG, "message=$message")
      Log.d(TAG, "title=$title")
      Log.d(TAG, "contentAvailable=$contentAvailable")
      Log.d(TAG, "forceStart=$forceStart")
      Log.d(TAG, "badgeCount=$badgeCount")
      Log.d(TAG, "isTapped =$tapped");

      val hasMessage = message != null && message.isNotEmpty()
      val hasTitle = title != null && title.isNotEmpty()

      if (hasMessage || hasTitle) {
        Log.d(TAG, "Create Notification")

        if (!hasTitle) {
          extras.putString(PushConstants.TITLE, getAppName(this))
        }

        createNotification(extras)
      }

      if (!isActive && forceStart == "1") {
        Log.d(TAG, "The app is not running, attempting to start in the background")

        val intent = Intent(this, PushHandlerActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          putExtra(PushConstants.PUSH_BUNDLE, extras)
          putExtra(PushConstants.START_IN_BACKGROUND, true)
          putExtra(PushConstants.FOREGROUND, false)
        }

        startActivity(intent)
      } else if (contentAvailable == "1") {
        Log.d(
          TAG,
          "The app is not running and content available is true, sending notification event"
        )

        sendExtras(extras)
      }
    }
  }

  private fun createNotification(extras: Bundle?) {
    val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val appName = getAppName(this)
    val notId = parseNotificationIdToInt(extras)
    val notificationIntent = Intent(this, PushHandlerActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      putExtra(PushConstants.PUSH_BUNDLE, extras)
      putExtra(PushConstants.NOT_ID, notId)
    }
    val random = SecureRandom()
    var requestCode = random.nextInt()
    val contentIntent = PendingIntent.getActivity(
      this,
      requestCode,
      notificationIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
    )
    val dismissedNotificationIntent = Intent(
      this,
      PushDismissedHandler::class.java
    ).apply {
      putExtra(PushConstants.PUSH_BUNDLE, extras)
      putExtra(PushConstants.NOT_ID, notId)
      putExtra(PushConstants.DISMISSED, true)

      action = PushConstants.PUSH_DISMISSED
    }

    requestCode = random.nextInt()

    val deleteIntent = PendingIntent.getBroadcast(
      this,
      requestCode,
      dismissedNotificationIntent,
      PendingIntent.FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE
    )

    val mBuilder: NotificationCompat.Builder =
      createNotificationBuilder(extras, mNotificationManager)

    mBuilder.setWhen(System.currentTimeMillis())
      .setContentTitle(fromHtml(extras?.getString(PushConstants.TITLE)))
      .setTicker(fromHtml(extras?.getString(PushConstants.TITLE)))
      .setContentIntent(contentIntent)
      .setDeleteIntent(deleteIntent)
      .setAutoCancel(true)

    val localIcon = pushSharedPref.getString(PushConstants.ICON, null)
    val localIconColor = pushSharedPref.getString(PushConstants.ICON_COLOR, null)
    val soundOption = pushSharedPref.getBoolean(PushConstants.SOUND, true)
    val vibrateOption = pushSharedPref.getBoolean(PushConstants.VIBRATE, true)

    Log.d(TAG, "stored icon=$localIcon")
    Log.d(TAG, "stored iconColor=$localIconColor")
    Log.d(TAG, "stored sound=$soundOption")
    Log.d(TAG, "stored vibrate=$vibrateOption")

    // Check if the notification behavior/content must be manually changed according to the value in its payload received from Firebase. 
    // The app will change according to the following:
    // - Type: It can be NORMAL and CRITICAL. Each type determines the behavior of the push when it is displayed on screen by the OS.
    // - Sound and Vibration: The payload will include more details about the sound and vibration values to be used in the notification.
    if (extras != null) {
      forcePhoneBehaviorOnCustomNotifications(context, extras, mBuilder)
    };

    /*
     * Notification Vibration
     */
    // Commented since the vibration will be flexed in the forcePhoneBehaviorOnCustomNotifications function.
    //setNotificationVibration(extras, vibrateOption, mBuilder)

    /*
     * Notification Icon Color
     *
     * Sets the small-icon background color of the notification.
     * To use, add the `iconColor` key to plugin android options
     */
    setNotificationIconColor(extras?.getString(PushConstants.COLOR), mBuilder, localIconColor)

    /*
     * Notification Icon
     *
     * Sets the small-icon of the notification.
     *
     * - checks the plugin options for `icon` key
     * - if none, uses the application icon
     *
     * The icon value must be a string that maps to a drawable resource.
     * If no resource is found, falls
     */
    setNotificationSmallIcon(extras, mBuilder, localIcon)

    /*
     * Notification Large-Icon
     *
     * Sets the large-icon of the notification
     *
     * - checks the gcm data for the `image` key
     * - checks to see if remote image, loads it.
     * - checks to see if assets image, Loads It.
     * - checks to see if resource image, LOADS IT!
     * - if none, we don't set the large icon
     */
    setNotificationLargeIcon(extras, mBuilder)

    /*
     * Notification Sound
     */
    // Commented since the sound will be flexed in the forcePhoneBehaviorOnCustomNotifications function.
    //if (soundOption) {
      //setNotificationSound(extras, mBuilder)
    //}

    /*
     *  LED Notification
     */
    setNotificationLedColor(extras, mBuilder)

    /*
     *  Priority Notification
     */
    setNotificationPriority(extras, mBuilder)

    /*
     * Notification message
     */
    setNotificationMessage(notId, extras, mBuilder)

    /*
     * Notification count
     */
    setNotificationCount(extras, mBuilder)

    /*
     *  Notification ongoing
     */
    setNotificationOngoing(extras, mBuilder)

    /*
     * Notification count
     */
    setVisibility(extras, mBuilder)

    /*
     * Notification add actions
     */
    createActions(extras, mBuilder, notId)

    // Get the number of notifications that are currently displayed in the Notification drawer.
    val activeNotifications = mNotificationManager.activeNotifications

    // If there is no items in the notification drawer when a notification is received then it should be displayed as a single item.
    // If not then a notification grouper will be displayed, since this grouper will replace the existing notifications and group them into a single item.
    if (activeNotifications.size == 0) {

      // Invoke the OS to display the single notification.
      mNotificationManager.notify(appName, notId, mBuilder.build())

    } else {

      // Remove from the notification drawer the existing grouper, since it will refreshed to include the content of the new received notification.
      if (activeNotifications[0].id != PushConstants.GROUP_NOTIFICATION_ID) {
        mNotificationManager.cancelAll()
      }

      // Create the grouper notification.
      if (extras != null) {
        displayGrouperNotification(extras, context, channelID, mNotificationManager)
      }
    }

  }

  /**
   * Function used to check if the received notification must change its behavior according to the following:
   * - Type: Critical and Normal notifications must be handled differently, since they have a sound and vibration that can be dynamically changed from the API validating its payload.
   * - Vibration and Sound: these values are overwritten to fix several problems encountered in this library.
   */
  private fun forcePhoneBehaviorOnCustomNotifications(
    context: Context,
    extras: Bundle,
    mBuilder: NotificationCompat.Builder
  ) {
    var ringtone: Ringtone? = null

    // Define a default vibration pattern.
    var pattern: LongArray? = null

    // Initialize the vibrator service.
    val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

    // Check if the payload defines a custom sound to play.
    val soundname = extras.getString(PushConstants.SOUND)
    var soundFile: Uri? = null

    // If the sound value is not present in the payload then we will omit the sound.
    if (soundname != null && !(soundname == "")) {
      if (soundname == PushConstants.SOUND_DEFAULT) {

        // Get the path of the default notification sound used by the phone.
        // Since each Android device uses its own sound, we must validate if the default sound is available otherwise we will get the sound of the alarm or ringtone.
        soundFile = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (soundFile == null) {
          soundFile = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
          if (soundFile == null) {
            soundFile = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
          }
        }
      } else {

        // Get the sound file from the app folder.
        soundFile =
          Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/raw/" + soundname)
      }

      // Initialize the ringtone service.
      ringtone = RingtoneManager.getRingtone(applicationContext, soundFile)
      // Always set the max volume of the phone to play the notification sound.
      ringtone.volume = 100f
    }

    // Check if the payload includes a vibration pattern to use.
    val vibrationPattern = extras.getString(PushConstants.VIBRATION_PATTERN)
    if (vibrationPattern != null) {

      // Check if the notification must vibrate.
      val items =
        vibrationPattern.replace("\\[".toRegex(), "").replace("\\]".toRegex(), "").split(",")
          .toTypedArray()

      if(items.size == 1 && items[0].trim().isEmpty()){

        // Vibration not specified.
        Log.d(TAG, "Vibration not specified.")
      } else {
        val results = LongArray(items.size)
        for (i in items.indices) {
          try {
            results[i] = items[i].trim { it <= ' ' }.toLong()
          } catch (nfe: java.lang.NumberFormatException) {
          }
        }

        // Assign the vibration pattern to use.
        pattern = results
      }
    }

    // Check the notification type.
    if (extras.getString(PushConstants.NOTIFICATION_CORES_TYPE) == PushConstants.NOTIFICATION_CRITICAL_CORES_TYPE) {
      Log.d(TAG, "Critical notification received")
      /**
       * IMPORTANT:
       * The phone must play a sound no matter if the phone is on silent/DND (Do Not Disturb phone mode), also must show the alert in the notification drawer and vibrate in a specific pattern.
       */

      // Since we added a new filter policy in the "Do not disturb" settings, we need to set the category of this push notification to be able to display it on screen
      // even if the "Do not disturb" is currently activated.
      mBuilder.setCategory(NotificationCompat.CATEGORY_ALARM)
      try {

        // Set the ringtone as ALARM to be able to play the sound no matter if the phone is silenced or in DND mode.
        ringtone!!.streamType = AudioManager.STREAM_ALARM

        // Initialize the audio services to be able to get/change the phone's sound settings.
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // Get the current sound setting.
        val currentRingerMode = audioManager.ringerMode
        // Change the sound mode to "Normal" that will allow us to play a sound.
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        if (ringtone != null) {

          // Play the sound.
          try {
            ringtone.play()

            // Return the sound settings to its original value (for example: if the phone was in silent mode and a critical notification is received then
            // we will change the sound settings to "Normal" and later we will return the settings to silent.
            audioManager.ringerMode = currentRingerMode
          } catch (e: java.lang.Exception) {
            e.printStackTrace()
          }
        }
      } catch (e: java.lang.Exception) {
        e.printStackTrace()
      }
    } else if (extras.getString(PushConstants.NOTIFICATION_CORES_TYPE) == PushConstants.NOTIFICATION_NORMAL_CORES_TYPE) {
      Log.d(TAG, "Normal notification received")

      // The phone will play a default system sound and a basic vibration.
      if (ringtone != null) {

        // Play the sound.
        try {

          // Set the ringtone type as a notification sound.
          ringtone.streamType = AudioManager.STREAM_NOTIFICATION
          ringtone.play()
        } catch (e: java.lang.Exception) {
          e.printStackTrace()
        }
      }
    }

    // Define the vibration patter.
    val audioAttributes = AudioAttributes.Builder()
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .setUsage(AudioAttributes.USAGE_ALARM)
      .build()

    // Vibrate the phone.
    try {
      if (pattern != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val ve = VibrationEffect.createWaveform(pattern, -1)
          vibrator.vibrate(ve, audioAttributes)
        } else {
          //deprecated in API 26
          vibrator.vibrate(pattern, -1)
        }
      }
    } catch (e: java.lang.Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Method used to create and display a local notification that will group into a single item all the received notifications of the notification drawer.
   * @param extras Bundle - object that will be stored in the notification.
   * @param context Context
   * @param channelID String - Channel used to create the notifications.
   * @param mNotificationManager NotificationManager
   */
  fun displayGrouperNotification(
    extras: Bundle,
    context: Context,
    channelID: String?,
    mNotificationManager: NotificationManager
  ) {
    val notificationsToGroup = HashMap<String, String?>()
    var random = SecureRandom()
    var requestCode = random.nextInt()
    var appName = getAppName(context)

    // Create the notification style. This is used to create a notification that will display as a list with the content of each notification.
    val notificationInbox = NotificationCompat.InboxStyle()
      .setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
    var countValidNotifications = 0

    // Iterate the list of items displayed in the notification drawer.
    for (key in messageMap.keys) {
      val receivedNotificationContent: ArrayList<String?>? = messageMap[key]
      for (i in receivedNotificationContent!!.size - 1 downTo 0) {

        // Ignore the notifications that are already processed (with an empty content).
        if (receivedNotificationContent.get(i)?.length!! > 0) {
          notificationInbox.addLine(fromHtml(receivedNotificationContent[i]))
          notificationsToGroup[key.toString()] = receivedNotificationContent[i]
          countValidNotifications++
        }
      }
    }

    // Prepare the summary text that will be displayed in the notification grouper.
    val sizeListMessage = countValidNotifications.toString()
    var stacking: String? = messageMap.size.toString() + " more"

    // Adjust the summary text according to the current number of notifications.
    if (extras.getString(PushConstants.SUMMARY_TEXT) != null) {
      if (countValidNotifications == 1) {
        stacking = "There is %n% notification".replace("%n%", sizeListMessage)
      } else {
        stacking = extras.getString(PushConstants.SUMMARY_TEXT)
        stacking = stacking!!.replace("%n%", sizeListMessage)
      }
    }
    notificationInbox.setSummaryText(fromHtml(stacking))

    // Set in the bundle the list of notification IDs that are grouped by this notification. The bundle is an object that represents the data of the notification.
    extras.putString(
      PushConstants.BUNDLE_KEY_OPEN_ALL_NOTIFICATIONS,
      coresNotificationIDlist.stream().collect(Collectors.joining(","))
    )

    // Also set in the bundle the list of the grouper notifications (including their content) as a JSON string.
    val jsonObject = JSONObject(notificationsToGroup as Map<*, *>?)
    extras.putString(
      PushConstants.BUNDLE_KEY_NOTIFICATIONS_GROUPED_WITH_CONTENT,
      jsonObject.toString()
    )

    // Create the intent that will help us to identify when this notification is manually dismissed by the user from the notification drawer.
    val dismissedNotificationIntent = Intent(context, PushDismissedHandler::class.java)
    dismissedNotificationIntent.putExtra(PushConstants.PUSH_BUNDLE, extras)
    dismissedNotificationIntent.putExtra(PushConstants.NOT_ID, PushConstants.GROUP_NOTIFICATION_ID)
    dismissedNotificationIntent.putExtra(PushConstants.DISMISSED, true)
    dismissedNotificationIntent.action = PushConstants.PUSH_DISMISSED
    requestCode = random.nextInt()
    var deleteIntent: PendingIntent? = null

    // Create the delete intent that will be invoked when the user dismisses manually this notification.
    deleteIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.getBroadcast(
        context, requestCode, dismissedNotificationIntent,
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
      )
    } else {
      PendingIntent.getBroadcast(
        context, requestCode, dismissedNotificationIntent,
        PendingIntent.FLAG_CANCEL_CURRENT
      )
    }

    // Create the intent that will be used to listen when this notification is clicked by the user from the notification drawer.
    val clickedNotificationIntent = Intent(context, PushHandlerActivity::class.java)
    clickedNotificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    clickedNotificationIntent.putExtra(PushConstants.PUSH_BUNDLE, extras)
    clickedNotificationIntent.putExtra(PushConstants.NOT_ID, PushConstants.GROUP_NOTIFICATION_ID)
    clickedNotificationIntent.putExtra(PushConstants.BUNDLE_KEY_OPEN_GROUPED_NOTIFICATIONS, true)
    random = SecureRandom()
    requestCode = random.nextInt()
    var contentIntent: PendingIntent? = null
    contentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.getActivity(
        context, requestCode, clickedNotificationIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      )
    } else {
      PendingIntent.getActivity(
        context, requestCode, clickedNotificationIntent,
        PendingIntent.FLAG_UPDATE_CURRENT
      )
    }

    // Create the notification that will be grouping the received notifications.
    val summaryBuilder = NotificationCompat.Builder(
      context,
      channelID!!
    )
      .setStyle(notificationInbox)
      .setExtras(extras)
      .setGroup(PushConstants.GROUP_KEY)
      .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      .setGroupSummary(true)
      .setDeleteIntent(deleteIntent)
      .setContentIntent(contentIntent)
      .setOnlyAlertOnce(true)
      .setVibrate(longArrayOf(0L))
      .setSilent(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      summaryBuilder.setSmallIcon(R.drawable.cores_mobile_push_icon)
    } else {
      summaryBuilder.setSmallIcon(context.applicationInfo.icon)
    }
    val summaryNotification = summaryBuilder.build()

    // Display in the notification drawer this notification that will group all notifications.
    mNotificationManager.notify(appName, PushConstants.GROUP_NOTIFICATION_ID, summaryNotification)
  }

  private fun createNotificationBuilder(
    extras: Bundle?,
    notificationManager: NotificationManager
  ): NotificationCompat.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

      if (extras != null) {
        channelID = extras.getString(PushConstants.ANDROID_CHANNEL_ID)
      }

      // if the push payload specifies a channel use it
      return if (channelID != null) {
        NotificationCompat.Builder(context, channelID!!)
      } else {
        val channels = notificationManager.notificationChannels

        channelID = if (channels.size == 1) {
          channels[0].id.toString()
        } else {
          PushConstants.DEFAULT_CHANNEL_ID
        }

        Log.d(TAG, "Using channel ID = $channelID")
        NotificationCompat.Builder(context, channelID!!)
      }
    } else {
      return NotificationCompat.Builder(context)
    }
  }

  private fun updateIntent(
    intent: Intent,
    callback: String,
    extras: Bundle?,
    foreground: Boolean,
    notId: Int,
  ) {
    intent.apply {
      putExtra(PushConstants.CALLBACK, callback)
      putExtra(PushConstants.PUSH_BUNDLE, extras)
      putExtra(PushConstants.FOREGROUND, foreground)
      putExtra(PushConstants.NOT_ID, notId)
    }
  }

  private fun createActions(
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder,
    notId: Int,
  ) {
    Log.d(TAG, "create actions: with in-line")

    if (extras == null) {
      Log.d(TAG, "create actions: extras is null, skipping")
      return
    }

    val actions = extras.getString(PushConstants.ACTIONS)
    if (actions != null) {
      try {
        val actionsArray = JSONArray(actions)
        val wActions = ArrayList<NotificationCompat.Action>()

        for (i in 0 until actionsArray.length()) {
          val min = 1
          val max = 2000000000
          val random = SecureRandom()
          val uniquePendingIntentRequestCode = random.nextInt(max - min + 1) + min

          Log.d(TAG, "adding action")

          val action = actionsArray.getJSONObject(i)

          Log.d(TAG, "adding callback = " + action.getString(PushConstants.CALLBACK))

          val foreground = action.optBoolean(PushConstants.FOREGROUND, true)
          val inline = action.optBoolean("inline", false)
          var intent: Intent?
          var pIntent: PendingIntent?
          val callback = action.getString(PushConstants.CALLBACK)

          when {
            inline -> {
              Log.d(TAG, "Version: ${Build.VERSION.SDK_INT} = ${Build.VERSION_CODES.M}")

              intent = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                Log.d(TAG, "Push Activity")
                Intent(this, PushHandlerActivity::class.java)
              } else {
                Log.d(TAG, "Push Receiver")
                Intent(this, BackgroundActionButtonHandler::class.java)
              }

              updateIntent(intent, callback, extras, foreground, notId)

              pIntent = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                Log.d(TAG, "push activity for notId $notId")

                PendingIntent.getActivity(
                  this,
                  uniquePendingIntentRequestCode,
                  intent,
                  PendingIntent.FLAG_ONE_SHOT or FLAG_MUTABLE
                )
              } else {
                Log.d(TAG, "push receiver for notId $notId")

                PendingIntent.getBroadcast(
                  this,
                  uniquePendingIntentRequestCode,
                  intent,
                  PendingIntent.FLAG_ONE_SHOT or FLAG_MUTABLE
                )
              }
            }

            foreground -> {
              intent = Intent(this, PushHandlerActivity::class.java)
              updateIntent(intent, callback, extras, foreground, notId)
              pIntent = PendingIntent.getActivity(
                this, uniquePendingIntentRequestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
              )
            }

            else -> {
              intent = Intent(this, BackgroundActionButtonHandler::class.java)
              updateIntent(intent, callback, extras, foreground, notId)
              pIntent = PendingIntent.getBroadcast(
                this, uniquePendingIntentRequestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
              )
            }
          }
          val actionBuilder = NotificationCompat.Action.Builder(
            getImageId(action.optString(PushConstants.ICON, "")),
            action.getString(PushConstants.TITLE),
            pIntent
          )

          var remoteInput: RemoteInput?

          if (inline) {
            Log.d(TAG, "Create Remote Input")

            val replyLabel = action.optString(
              PushConstants.INLINE_REPLY_LABEL,
              "Enter your reply here"
            )

            remoteInput = RemoteInput.Builder(PushConstants.INLINE_REPLY)
              .setLabel(replyLabel)
              .build()

            actionBuilder.addRemoteInput(remoteInput)
          }

          val wAction: NotificationCompat.Action = actionBuilder.build()
          wActions.add(actionBuilder.build())

          if (inline) {
            mBuilder.addAction(wAction)
          } else {
            mBuilder.addAction(
              getImageId(action.optString(PushConstants.ICON, "")),
              action.getString(PushConstants.TITLE),
              pIntent
            )
          }
        }

        mBuilder.extend(NotificationCompat.WearableExtender().addActions(wActions))
        wActions.clear()
      } catch (e: JSONException) {
        // nope
      }
    }
  }

  private fun setNotificationCount(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    val count = extractBadgeCount(extras)
    if (count >= 0) {
      Log.d(TAG, "count =[$count]")
      mBuilder.setNumber(count)
    }
  }

  private fun setVisibility(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    extras?.getString(PushConstants.VISIBILITY)?.let { visibilityStr ->
      try {
        val visibilityInt = visibilityStr.toInt()

        if (
          visibilityInt >= NotificationCompat.VISIBILITY_SECRET
          && visibilityInt <= NotificationCompat.VISIBILITY_PUBLIC
        ) {
          mBuilder.setVisibility(visibilityInt)
        } else {
          Log.e(TAG, "Visibility parameter must be between -1 and 1")
        }
      } catch (e: NumberFormatException) {
        e.printStackTrace()
      }
    }
  }

  private fun setNotificationVibration(
    extras: Bundle?,
    vibrateOption: Boolean,
    mBuilder: NotificationCompat.Builder,
  ) {
    if (extras == null) {
      Log.d(TAG, "setNotificationVibration: extras is null, skipping")
      return
    }

    val vibrationPattern = extras.getString(PushConstants.VIBRATION_PATTERN)
    if (vibrationPattern != null) {
      val items = convertToTypedArray(vibrationPattern)
      val results = LongArray(items.size)
      for (i in items.indices) {
        try {
          results[i] = items[i].trim { it <= ' ' }.toLong()
        } catch (nfe: NumberFormatException) {
        }
      }
      mBuilder.setVibrate(results)
    } else {
      if (vibrateOption) {
        mBuilder.setDefaults(Notification.DEFAULT_VIBRATE)
      }
    }
  }

  private fun setNotificationOngoing(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    extras?.getString(PushConstants.ONGOING, "false")?.let {
      mBuilder.setOngoing(it.toBoolean())
    }
  }

  private fun setNotificationMessage(
    notId: Int,
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder,
  ) {
    extras?.let {
      val message = it.getString(PushConstants.MESSAGE)

      // Set in the extra of the notification the ID that represents this item inside the CORES Mobile app.
      // This will be helpful to identify this notification inside the CORES Mobile app when it is pressed by the user.
      for (key in extras.keySet()) {
        if (key == "coresPayload") {
          val jsonStringCoresPayload = extras[key].toString()
          var jsonCoresPayload: JSONObject? = null
          try {
            jsonCoresPayload = JSONObject(jsonStringCoresPayload)

            // The notification ID will indicate to the CORES Mobile app the reminder that should be displayed in the Inbox list.
            val coresNotificationID = jsonCoresPayload.getString("notification_id")
            mBuilder.extras.putString(PushConstants.BUNDLE_KEY_CORES_NOTIFICATION_ID, coresNotificationID)

            // Store the CORES notification ID to use it later.
            setCoresNotificationIDTolist(coresNotificationID)
            break
          } catch (e: JSONException) {
            e.printStackTrace()
          }
        }
      }

      when (it.getString(PushConstants.STYLE, PushConstants.STYLE_TEXT)) {
        PushConstants.STYLE_INBOX -> {
          setNotification(notId, message)
          mBuilder.setContentText(fromHtml(message))

          messageMap[notId]?.let { messageList ->
            val sizeList = messageList.size

            if (sizeList > 1) {
              val sizeListMessage = sizeList.toString()
              var stacking: String? = "$sizeList more"

              it.getString(PushConstants.SUMMARY_TEXT)?.let { summaryText ->
                stacking = summaryText.replace("%n%", sizeListMessage)
              }

              val notificationInbox = NotificationCompat.InboxStyle().run {
                setBigContentTitle(fromHtml(it.getString(PushConstants.TITLE)))
                setSummaryText(fromHtml(stacking))
              }.also { inbox ->
                for (i in messageList.indices.reversed()) {
                  inbox.addLine(fromHtml(messageList[i]))
                }
              }

              mBuilder.setStyle(notificationInbox)
            } else {
              message?.let { message ->
                val bigText = NotificationCompat.BigTextStyle().run {
                  bigText(fromHtml(message))
                  setBigContentTitle(fromHtml(it.getString(PushConstants.TITLE)))
                }

                mBuilder.setStyle(bigText)
              }
            }
          }
        }

        PushConstants.STYLE_PICTURE -> {
          setNotification(notId, "")
          val bigPicture = NotificationCompat.BigPictureStyle().run {
            bigPicture(getBitmapFromURL(it.getString(PushConstants.PICTURE)))
            setBigContentTitle(fromHtml(it.getString(PushConstants.TITLE)))
            setSummaryText(fromHtml(it.getString(PushConstants.SUMMARY_TEXT)))
          }

          mBuilder.apply {
            setContentTitle(fromHtml(it.getString(PushConstants.TITLE)))
            setContentText(fromHtml(message))
            setStyle(bigPicture)
          }
        }

        else -> {
          setNotification(notId, "")

          message?.let { messageStr ->
            val bigText = NotificationCompat.BigTextStyle().run {
              bigText(fromHtml(messageStr))
              setBigContentTitle(fromHtml(it.getString(PushConstants.TITLE)))

              it.getString(PushConstants.SUMMARY_TEXT)?.let { summaryText ->
                setSummaryText(fromHtml(summaryText))
              }
            }

            mBuilder.setContentText(fromHtml(messageStr))
            mBuilder.setStyle(bigText)
          }
        }
      }
    }
  }

  private fun setNotificationSound(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    extras?.let {
      val soundName = it.getString(PushConstants.SOUNDNAME) ?: it.getString(PushConstants.SOUND)

      when {
        soundName == PushConstants.SOUND_RINGTONE -> {
          mBuilder.setSound(Settings.System.DEFAULT_RINGTONE_URI)
        }

        soundName != null && !soundName.contentEquals(PushConstants.SOUND_DEFAULT) -> {
          val sound = Uri.parse(
            "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/$soundName"
          )

          Log.d(TAG, "Sound URL: $sound")

          mBuilder.setSound(sound)
        }

        else -> {
          mBuilder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
        }
      }
    }
  }

  private fun convertToTypedArray(item: String): Array<String> {
    return item.replace("\\[".toRegex(), "")
      .replace("]".toRegex(), "")
      .split(",")
      .toTypedArray()
  }

  private fun setNotificationLedColor(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    extras?.let { it ->
      it.getString(PushConstants.LED_COLOR)?.let { ledColor ->
        // Convert ledColor to Int Typed Array
        val items = convertToTypedArray(ledColor)
        val results = IntArray(items.size)

        for (i in items.indices) {
          try {
            results[i] = items[i].trim { it <= ' ' }.toInt()
          } catch (nfe: NumberFormatException) {
            Log.e(TAG, "Number Format Exception: $nfe")
          }
        }

        if (results.size == 4) {
          val (alpha, red, green, blue) = results
          mBuilder.setLights(Color.argb(alpha, red, green, blue), 500, 500)
        } else {
          Log.e(TAG, "ledColor parameter must be an array of length == 4 (ARGB)")
        }
      }
    }
  }

  private fun setNotificationPriority(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    extras?.let { it ->
      it.getString(PushConstants.PRIORITY)?.let { priorityStr ->
        try {
          val priority = priorityStr.toInt()

          if (
            priority >= NotificationCompat.PRIORITY_MIN
            && priority <= NotificationCompat.PRIORITY_MAX
          ) {
            mBuilder.priority = priority
          } else {
            Log.e(TAG, "Priority parameter must be between -2 and 2")
          }
        } catch (e: NumberFormatException) {
          e.printStackTrace()
        }
      }
    }
  }

  private fun getCircleBitmap(bitmap: Bitmap?): Bitmap? {
    if (bitmap == null) {
      return null
    }

    val output = Bitmap.createBitmap(
      bitmap.width,
      bitmap.height,
      Bitmap.Config.ARGB_8888
    )

    val paint = Paint().apply {
      isAntiAlias = true
      color = Color.RED
      xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }

    Canvas(output).apply {
      drawARGB(0, 0, 0, 0)

      val cx = (bitmap.width / 2).toFloat()
      val cy = (bitmap.height / 2).toFloat()
      val radius = if (cx < cy) cx else cy
      val rect = Rect(0, 0, bitmap.width, bitmap.height)

      drawCircle(cx, cy, radius, paint)
      drawBitmap(bitmap, rect, rect, paint)
    }

    bitmap.recycle()
    return output
  }

  private fun setNotificationLargeIcon(
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder,
  ) {
    extras?.let {
      val gcmLargeIcon = it.getString(PushConstants.IMAGE)
      val imageType = it.getString(PushConstants.IMAGE_TYPE, PushConstants.IMAGE_TYPE_SQUARE)

      if (gcmLargeIcon != null && gcmLargeIcon != "") {
        if (
          gcmLargeIcon.startsWith("http://")
          || gcmLargeIcon.startsWith("https://")
        ) {
          val bitmap = getBitmapFromURL(gcmLargeIcon)

          if (PushConstants.IMAGE_TYPE_SQUARE.equals(imageType, ignoreCase = true)) {
            mBuilder.setLargeIcon(bitmap)
          } else {
            val bm = getCircleBitmap(bitmap)
            mBuilder.setLargeIcon(bm)
          }

          Log.d(TAG, "Using remote large-icon from GCM")
        } else {
          try {
            val inputStream: InputStream = assets.open(gcmLargeIcon)

            val bitmap = BitmapFactory.decodeStream(inputStream)

            if (PushConstants.IMAGE_TYPE_SQUARE.equals(imageType, ignoreCase = true)) {
              mBuilder.setLargeIcon(bitmap)
            } else {
              val bm = getCircleBitmap(bitmap)
              mBuilder.setLargeIcon(bm)
            }

            Log.d(TAG, "Using assets large-icon from GCM")
          } catch (e: IOException) {
            val largeIconId: Int = getImageId(gcmLargeIcon)

            if (largeIconId != 0) {
              val largeIconBitmap = BitmapFactory.decodeResource(context.resources, largeIconId)
              mBuilder.setLargeIcon(largeIconBitmap)
              Log.d(TAG, "Using resources large-icon from GCM")
            } else {
              Log.d(TAG, "Not large icon settings")
            }
          }
        }
      }
    }
  }

  private fun getImageId(icon: String): Int {
    var iconId = context.resources.getIdentifier(icon, PushConstants.DRAWABLE, context.packageName)
    if (iconId == 0) {
      iconId = context.resources.getIdentifier(icon, "mipmap", context.packageName)
    }
    return iconId
  }

  private fun setNotificationSmallIcon(
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder,
    localIcon: String?,
  ) {
    extras?.let {
      val icon = it.getString(PushConstants.ICON)

      val iconId = when {
        icon != null && icon != "" -> {
          getImageId(icon)
        }

        localIcon != null && localIcon != "" -> {
          getImageId(localIcon)
        }

        else -> {
          Log.d(TAG, "No icon resource found from settings, using application icon")
          context.applicationInfo.icon
        }
      }

      // Fix for the issue: https://github.com/havesource/cordova-plugin-push/issues/214 - Push notification icon missing (showing white box instead of app icon) 
      // in notification tray in Android 12 (SDK 32).
      // Since this problem has not been fixed by the authors, CORES Mobile app applies the following fix: 
      // - The "config.xml" file is manually adding in the "drawable" folder the "cores_mobile_push_icon" image files that will be used as icon when a notification 
      //    is displayed in the notification drawer. These files are moved to the compiled app when the "cordova build..." command runs.
      // Check the current OS version.
      if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // Load from the resource folder (drawable) the icon of the app.
        mBuilder.setSmallIcon(R.drawable.cores_mobile_push_icon);
      } else {
        // Use the default icon of the app.
        mBuilder.setSmallIcon(iconId);
      }
    }
  }

  private fun setNotificationIconColor(
    color: String?,
    mBuilder: NotificationCompat.Builder,
    localIconColor: String?,
  ) {
    val iconColor = when {
      color != null && color != "" -> {
        try {
          Color.parseColor(color)
        } catch (e: IllegalArgumentException) {
          Log.e(TAG, "Couldn't parse color from Android options")
        }
      }

      localIconColor != null && localIconColor != "" -> {
        try {
          Color.parseColor(localIconColor)
        } catch (e: IllegalArgumentException) {
          Log.e(TAG, "Couldn't parse color from android options")
        }
      }

      else -> {
        Log.d(TAG, "No icon color settings found")
        0
      }
    }

    if (iconColor != 0) {
      mBuilder.color = iconColor
    }
  }

  private fun getBitmapFromURL(strURL: String?): Bitmap? {
    return try {
      val url = URL(strURL)
      val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        doInput = true
        connect()
      }
      val input = connection.inputStream
      BitmapFactory.decodeStream(input)
    } catch (e: IOException) {
      e.printStackTrace()
      null
    }
  }

  private fun parseNotificationIdToInt(extras: Bundle?): Int {
    var returnVal = 0

    try {
      returnVal = extras!!.getString(PushConstants.NOT_ID)!!.toInt()
    } catch (e: NumberFormatException) {
      Log.e(TAG, "NumberFormatException occurred: ${PushConstants.NOT_ID}: ${e.message}")
    } catch (e: Exception) {
      Log.e(TAG, "Exception occurred when parsing ${PushConstants.NOT_ID}: ${e.message}")
    }

    return returnVal
  }

  private fun fromHtml(source: String?): Spanned? {
    return if (source != null) Html.fromHtml(source) else null
  }

  private fun isAvailableSender(from: String?): Boolean {
    val savedSenderID = pushSharedPref.getString(PushConstants.SENDER_ID, "")
    Log.d(TAG, "sender id = $savedSenderID")
    return from == savedSenderID || from!!.startsWith("/topics/")
  }
}
