package pl.antyradek.pinpolice;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.constraint.ConstraintLayout;
import android.support.design.widget.BottomNavigationView;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    public static ImageView imageView;

    private ScrollView mainView;
    private LinearLayout mainLinearLayout;

    private BottomNavigationView.OnNavigationItemSelectedListener mOnNavigationItemSelectedListener
            = new BottomNavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            //usuń wszystko, co wcześniej
            mainLinearLayout.removeAllViews();

            switch (item.getItemId()) {
                case R.id.navigation_dashboard:
                    mainLinearLayout.addView(View.inflate(getApplicationContext(), R.layout.dashboard, null));
                    return true;
                case R.id.navigation_camera_view:
                    mainLinearLayout.addView(View.inflate(getApplicationContext(), R.layout.camera_view, null));
                    imageView = findViewById(R.id.imageView);
                    imageView.setImageResource(R.drawable.ic_home_black_24dp);
                    imageView.setY(100);
                    return true;
                case R.id.navigation_map:
                    mainLinearLayout.addView(View.inflate(getApplicationContext(), R.layout.map, null));
                    return true;

            }
            return false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //ustaw słuchanie przełączeń elementów menu
        BottomNavigationView navigation = (BottomNavigationView) findViewById(R.id.navigation);
        navigation.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener);

        //znajdź elementy GUI
        mainView = (ScrollView) findViewById(R.id.main_view);
        mainLinearLayout = (LinearLayout) findViewById(R.id.main_linear_layout);

        //ustaw temat (jakiś bug i nie robi tego automatycznie)
        getApplicationContext().setTheme(R.style.AppTheme);
        //ustaw pierwszy widok na start
        mainLinearLayout.addView(View.inflate(getApplicationContext(), R.layout.dashboard, null));

        //poproś o pozwolenie na kamerę
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            {
                //wywoła callback
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION}, 12345);
            }
            else
            {
                //już dane pozwolenie
                startCameraService();
            }
        }
        else
            {
            //startuje kamerę
            startCameraService();
        }
    }

    void startCameraService(){
        if(imageView==null)
            imageView = findViewById(R.id.imageView);
        //wystartuj serwis kamery
        Intent intent = new Intent(this, CameraService.class);
        this.startService(intent);
    }

    /** Wywołane na zezwolenie na kamerę */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if(permissions.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
        {
            //zezwolono na aparat
            startCameraService();
        }
        else
        {
            //powiedz, że to niefajne
            Toast.makeText(this, R.string.camera_request_description, Toast.LENGTH_LONG);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.stopService(new Intent(this, CameraService.class));
    }
}
