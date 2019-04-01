package pl.antyradek.pinpolice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

/** Serwis działający w tle do odczytywania obrazów z kamery i interpretacji obrazu */
public class CameraService extends Service {



    /** Stwarza cały serwis */
    @Override
    public void onCreate() {
        super.onCreate();

        //stwórz powiadomienie
        final int foregroundServiceId = 1234;

        Notification.Builder builder;
        //dla nowszych wersji trzeba stworzyć kanał powiadomień
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            final String notificationChannelId = "PINPOLICE_NOTIFICATION_CHANNEL_ID";
            builder = new Notification.Builder(this, notificationChannelId);
            //stwórz kanał powiadomień
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel notificationChannel = new NotificationChannel(notificationChannelId, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(notificationChannel);
        }
        else
        {
            builder = new Notification.Builder(this);
        }

        //ustaw parametry powiadomienia
        builder.setContentTitle(getString(R.string.camera_notification))
                .setSmallIcon(R.drawable.ic_camera_black_24dp);

        //przestaw serwis na pierwszy plan (potrzebne żeby używać aparatu)
        startForeground(foregroundServiceId, builder.build());

        //TODO uruchom aparat

    }

    /** Zawołane, gdy inna czynność spróbje zbindować serwis */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "Serwis kamery uruchomiony", Toast.LENGTH_SHORT).show();



        //TODO uruchom aparat

        return Service.START_STICKY;
    }
}
