package pl.antyradek.pinpolice;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Toast;
import android.graphics.Matrix;
import pl.antyradek.pinpolice.env.ImageUtils;
import java.util.List;
import android.graphics.Canvas;
import pl.antyradek.pinpolice.env.Logger;
import android.media.RingtoneManager;
import android.media.Ringtone;
import android.net.Uri;

import java.io.IOException;

/** Serwis działający w tle do odczytywania obrazów z kamery i interpretacji obrazu */
public class CameraService extends Service implements Camera.PreviewCallback, LocationListener {

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

    private Classifier classifier;

    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;
    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;
    protected int previewWidth = 0;
    protected int previewHeight = 0;
    private Integer sensorOrientation;

    private int[] rgbBytes = null;

    private static final int INPUT_SIZE = 224;
    private static final int IMAGE_MEAN = 117;
    private static final float IMAGE_STD = 1;
    private static final String INPUT_NAME = "resnet50_input";
    private static final String OUTPUT_NAME = "dense/Softmax";

    private static final boolean MAINTAIN_ASPECT = true;

    private Runnable imageConverter;

    private static final Logger LOGGER = new Logger();

    /** Ostatnia znana lokacja, tudzież null */
    private Location lastLocation;

    /** Gdy nie działa, nie wołaj żadnych systemowych metod */
    private boolean isRunning;

    Handler handler;

    private boolean isNNThreadRunning = false;
    private Classifier.Recognition lastNNResult;

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

        classifier =
                TensorFlowImageClassifier.create(
                        getAssets(),
                        "file:///android_asset/tf_model.pb",
                        "file:///android_asset/labels.txt",
                        INPUT_SIZE,
                        IMAGE_MEAN,
                        IMAGE_STD,
                        INPUT_NAME,
                        OUTPUT_NAME);

        previewWidth = mainCamera.getParameters().getPreviewSize().width;
        previewHeight = mainCamera.getParameters().getPreviewSize().height;

        sensorOrientation = 0; //TODO

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                INPUT_SIZE, INPUT_SIZE,
                sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        if (rgbBytes == null) {
            rgbBytes = new int[previewWidth * previewHeight];
        }

        //GPS
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        String provider = locationManager.getBestProvider(criteria, false);
        //lokalizacja powinna być zezwolona w MainActivity
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            {
                throw new RuntimeException("Brak pozwolenia na lokalizację");
            }
        }
        LOGGER.i("Najlepszy dostawca lokalizacji: " + provider);

        locationManager.requestLocationUpdates(provider, 400, 1, this);

        this.isRunning = true;
        handler = new Handler();
    }

    private void runOnUiThread(Runnable runnable) {
        handler.post(runnable);
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
        this.mainCamera.setPreviewCallback(null);
        this.mainCamera.stopPreview();
        this.mainCamera.release();
        this.mainCamera = null;
        this.isRunning = false;
    }

    /** Wołane na każdą ramkę kamery */
    @Override
    public void onPreviewFrame(final byte[] data, Camera camera) {
        //NOTE data to jakiś YUV obiekt, nie da się bezpośrednio RGB wyciągnąć

        //TODO operacje na sieci neuronowej

        //DEBUG oblicz jasność obrazu
        int sum = 0;
        for(byte pixel : data)
        {
            sum += pixel;
        }
        double mean = 1.0 * sum / data.length;

        imageConverter =
                new Runnable() {
                    @Override
                    public void run() {
                        ImageUtils.convertYUV420SPToARGB8888(data, previewWidth, previewHeight, rgbBytes);
                    }
                };

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);
        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                if(MainActivity.imageView != null)
                {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(90);
                    Bitmap rotatedBitmap = Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.getWidth(), croppedBitmap.getHeight(), matrix, true);
                    MainActivity.imageView.setImageBitmap(rotatedBitmap);
                }
                else
                    LOGGER.i("MainActivity.imageView is null!");
            }
        });

        Thread t = new Thread(new Runnable() {
            public void run() {
                final List<Classifier.Recognition> results = classifier.recognizeImage(croppedBitmap);
                lastNNResult = null;

                for(Classifier.Recognition tr : results) {
                    //LOGGER.i("Detect: %s", tr);
                    if (tr.getTitle().equals("radiowozy")) {
                        LOGGER.i("Rozpoznano: %s", tr);
                        lastNNResult = tr;
                        try {
                            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                            Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
                            r.play();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                isNNThreadRunning = false;
            }
        });

        if(!isNNThreadRunning)
        {
            isNNThreadRunning = true;
            t.start();
        }
        if(lastNNResult != null)
        {
            Toast.makeText(this, "Detect:" + lastNNResult, Toast.LENGTH_LONG).show();
            if(MainActivity.rozpoznanieTextView != null) {
                float tmpConf=0.0f;
                if(lastNNResult != null && lastNNResult.getConfidence() != null)
                {
                    try{
                        tmpConf=lastNNResult.getConfidence()*100.0f;
                    }
                    catch(Exception e)
                    {
                        tmpConf=0;
                        e.printStackTrace();
                    }
                }
                MainActivity.rozpoznanieTextView.setText("Radiowóz na: " + tmpConf + "%");
                MainActivity.rozpoznanieTextView.setTextColor(Color.RED);
                MainActivity.rozpoznanieTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP,25);
            }
        }
        else
        {
            if(MainActivity.rozpoznanieTextView != null)
            {
                MainActivity.rozpoznanieTextView.setText("Nie rozpoznano...");
                MainActivity.rozpoznanieTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
                MainActivity.rozpoznanieTextView.setTextColor(Color.WHITE);
            }
        }

        //przerób na bitmapę
        //Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        //Bitmap bitmap = Bitmap.createBitmap(PREVIEW_WIDH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        //bitmap.eraseColor(Color.MAGENTA);

        //GLES20.glReadPixels(); ?

        //zaktualizuj powiadomienie
        String contentText = new String();
        //contentText += "\"Jasność\": " + mean;
        contentText += "Pozycja: ";
        if(this.lastLocation != null){
             contentText += lastLocation.getLatitude() + " × " + lastLocation.getLongitude();
        }
        else
        {
            contentText += "Nieznana";
        }
        this.notificationBuilder.setContentText(contentText);

        //this.notificationBuilder.setLargeIcon(bitmap);
        if(this.isRunning)
        {
            //wyłącza aplikację
            startForeground(FOREGROUND_SERVICE_ID, this.notificationBuilder.build());
        }
    }

    protected int[] getRgbBytes() {
        imageConverter.run();
        return rgbBytes;
    }

    @Override
    public void onLocationChanged(Location location) {
        Toast toast = Toast.makeText(this, "Pozycja: " + location.toString(), Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | Gravity.LEFT, 0, 0);
        toast.show();
        this.lastLocation = location;
        LOGGER.i("Zmieniona pozycja: " + location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Toast toast = Toast.makeText(this, "Status: " + status, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | Gravity.LEFT, 0, 0);
        toast.show();
        LOGGER.i("Zmieniony stan: " + status);
    }

    @Override
    public void onProviderEnabled(String provider) {
        Toast toast = Toast.makeText(this, "Włączono dostawcę: " + provider, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | Gravity.LEFT, 0, 0);
        toast.show();
        LOGGER.i("Włączony dostawcan: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Toast toast = Toast.makeText(this, "Wyłączono dostawcę: " + provider, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | Gravity.LEFT, 0, 0);
        toast.show();
        LOGGER.i("Wyłączony dostawcan: " + provider);
    }
}
