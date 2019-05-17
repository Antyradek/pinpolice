package pl.antyradek.pinpolice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.Console;
import java.io.IOException;

/** Serwis działający w tle do odczytywania obrazów z kamery i interpretacji obrazu */
public class CameraService extends Service implements Camera.PreviewCallback {

    /** Używany aparat */
    private Camera mainCamera;

    /** Rozmiar podglądu */
    final int PREVIEW_WIDH = 256;
    final int PREVIEW_HEIGHT = 256;

    /** Identyfikator usugi */
    final int FOREGROUND_SERVICE_ID = 12345;

    /** Budowacz powiadomienia do aktualizacji tegoż */
    Notification.Builder notificationBuilder;

    /** Pamięć kamery */
    SurfaceTexture surfaceTexture;

    /** Stwarza cały serwis */
    @Override
    public void onCreate() {
        super.onCreate();

        //stwórz powiadomienie
        //dla nowszych wersji trzeba stworzyć kanał powiadomień
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            final String notificationChannelId = "PINPOLICE_NOTIFICATION_CHANNEL_ID";
            this.notificationBuilder = new Notification.Builder(this, notificationChannelId);
            //stwórz kanał powiadomień
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel notificationChannel = new NotificationChannel(notificationChannelId, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(notificationChannel);
        }
        else
        {
            this.notificationBuilder = new Notification.Builder(this);
        }

        //ustaw parametry powiadomienia
        this.notificationBuilder.setContentTitle(getString(R.string.camera_notification))
                .setContentText("Jasność nieokreślona")
                .setSmallIcon(R.drawable.ic_camera_black_24dp);

        //przestaw serwis na pierwszy plan (potrzebne żeby używać aparatu)
        startForeground(FOREGROUND_SERVICE_ID, this.notificationBuilder.build());

        //uruchom aparat
        this.mainCamera = Camera.open();
        try
        {
            Camera.Parameters cameraParameters = this.mainCamera.getParameters();
            //NOTE poniższa rzuci wyjątkiem na Pie lub na emulatorze

            //TODO wielkości nie są obsługiwane zawsze i wszędzie, trzeba by wybrać jak jak najbardziej zbliżoną wielkość
            //TODO wysłać wielkości do menu?
            for (Camera.Size size: cameraParameters.getSupportedPreviewSizes())
            {
                if(size.height > PREVIEW_HEIGHT && size.width > PREVIEW_WIDH)
                {
                    cameraParameters.setPreviewSize(size.width, size.height);
                    break;
                }
            }
            this.mainCamera.setParameters(cameraParameters);
        }
        catch(RuntimeException error)
        {
            Toast.makeText(this, "Nie można ustawić parametrów aparatu", Toast.LENGTH_LONG).show();
            //kontynuujemy dalej
        }

        this.surfaceTexture = new SurfaceTexture(111);
        try
        {
            this.mainCamera.setPreviewTexture(surfaceTexture);
        }
        catch(IOException error){
            Toast.makeText(this, "Tekstura nie może być stworzona", Toast.LENGTH_LONG).show();
            return;
        }
        this.mainCamera.setPreviewCallback(this);
    }

    /** Zawołane, gdy inna czynność spróbje zbindować serwis */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "Kamera uruchomiona", Toast.LENGTH_SHORT).show();
        Toast.makeText(this, "Wielkość aparatu: " + mainCamera.getParameters().getPreviewSize().width + "×" + mainCamera.getParameters().getPreviewSize().height, Toast.LENGTH_LONG).show();

        //uruchom aparat
        this.mainCamera.startPreview();

        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.mainCamera.stopPreview();
        this.mainCamera.release();
    }

    /** Wołane na każdą ramkę kamery */
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        //NOTE data to jakiś YUV obiekt, nie da się bezpośrednio RGB wyciągnąć

        //TODO operacje na sieci neuronowej

        //DEBUG oblicz jasność obrazu
        int sum = 0;
        for(byte pixel : data)
        {
            sum += pixel;
        }
        double mean = 1.0 * sum / data.length;

        //przerób na bitmapę
        //Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        //Bitmap bitmap = Bitmap.createBitmap(PREVIEW_WIDH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        //bitmap.eraseColor(Color.MAGENTA);

        //GLES20.glReadPixels(); ?

        //zaktualizuj powiadomienie
        this.notificationBuilder.setContentText("\"Jasność\": " + mean);
        //this.notificationBuilder.setLargeIcon(bitmap);
        startForeground(FOREGROUND_SERVICE_ID, this.notificationBuilder.build());
    }
}
