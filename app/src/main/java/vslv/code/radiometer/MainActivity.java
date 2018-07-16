package vslv.code.radiometer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;


public class MainActivity extends AppCompatActivity {

    private static final int REQ_ENABLE_BLUETOOTH = 1001;
    public final String TAG = getClass().getSimpleName();

    private TextView alert_lim;
    private TextView alert_switch;
    //private Button searchAgain;
    private LinearLayout meas_layout;
    private TextView sound_control;
    private TextView sensor;
    private TextView average;
    private TextView battery;
    private TextView unit;
    private TextView measured;
    private TextView measHint;

    private boolean sound_enabled = false;
    private boolean unit_mksvph = true;
    private double receivedVoltage = 0;
    private int mkrph;
    private int mkrph_avg;

    private int counter_meas_lim = 8; //чем больше, тем точнее, но медленнее
    private boolean measure_started = false;
    private int counter_meas = 0;
    private ArrayList<Integer> mkrph_meas = new ArrayList<>();

    private BluetoothAdapter mBluetoothAdapter;
    private ProgressDialog mProgressDialog;
    private ArrayList<BluetoothDevice> mDevices = new ArrayList<>();
    private DeviceListAdapter mDeviceListAdapter;

    private BluetoothSocket mBluetoothSocket;
    private OutputStream mOutputStream;
    private InputStream mInputStream;
    private Thread workerThread;
    private byte[] readBuffer;
    private int readBufferPosition;
    private volatile boolean stopWorker;

    private boolean alert = false;
    private int alert_lim_counter = 0;
    private int active_lim_mkrph = 2147483647;
    private boolean first_lim_act = true;

    private ListView listDevices;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //mSettings = getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sound_control = findViewById(R.id.sound);
        sensor = findViewById(R.id.textView);
        average = findViewById(R.id.textView3);
        battery = findViewById(R.id.textView4);
        unit = findViewById(R.id.textView2);
        alert_lim = findViewById(R.id.sig_alert);
        alert_switch = findViewById(R.id.signal);
        measured = findViewById(R.id.meas);
        //searchAgain = findViewById(R.id.start_again_search);
        measHint = findViewById(R.id.meas_hint);
        meas_layout = findViewById(R.id.meas_layout);

        sound_control.setOnClickListener(clickListener);
        //searchAgain.setOnClickListener(clickListener);
        unit.setOnClickListener(clickListener);
        alert_lim.setOnClickListener(clickListener);
        alert_switch.setOnClickListener(clickListener);
        meas_layout.setOnClickListener(clickListener);


        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.d(TAG, "Устройство не поддерживает Bluetooth");
            finish();
        }

        mDeviceListAdapter = new DeviceListAdapter(this, R.layout.device_item, mDevices);
        setUnitVisibility(false);
        setControlButtonsVisibility(false);
        //setConnectButtonsVisibility(false);
        // включаем bluetooth
        if (mBluetoothAdapter.isEnabled()) {
            searchDevices();
        }
        else {
            enableBluetooth();
            while (!mBluetoothAdapter.isEnabled()) {
                Log.d(TAG, "Ожидаем включения bluetooth");
            }
            searchDevices();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mBluetoothSocket != null) {
                mBluetoothSocket.close();
            }
            if (mOutputStream != null) {
                mOutputStream.close();
            }
            if (mInputStream != null) {
                mInputStream.close();
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.item_search:
                searchDevices();
                break;

            case R.id.item_exit:
                finish();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public void onBackPressed() {
        //  super.onBackPressed();
        moveTaskToBack(true);
        showToastMessage("Приложение работает в фоне");
        //Сворачивает приложение при нажатии кнопки назад
    }

    //Запускает поиск bluetooth устройств
    private void searchDevices() {
        Log.d(TAG, "searchDevices()");
        enableBluetooth();

        checkPermissionLocation();

        if (!mBluetoothAdapter.isDiscovering()) {
            Log.d(TAG, "searchDevices: начинаем поиск устройств.");
            mBluetoothAdapter.startDiscovery();
        }

        if (mBluetoothAdapter.isDiscovering()) {
            Log.d(TAG, "searchDevices: поиск уже был запущен... перезапускаем его еще раз.");
            mBluetoothAdapter.cancelDiscovery();
            mBluetoothAdapter.startDiscovery();
        }

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        registerReceiver(mRecevier, filter);
    }


    //Показывает диалоговое окно со списком найденых устройств
    private void showListDevices() {
        Log.d(TAG, "showListDevices()");
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Найденые устройства");

        View view = getLayoutInflater().inflate(R.layout.list_devices_view, null);
        listDevices = view.findViewById(R.id.list_devices);
        listDevices.setAdapter(mDeviceListAdapter);
        listDevices.setOnItemClickListener(itemOnClickListener);

        builder.setView(view);
        /**
         *Кнопка для сохранения MAC устройства пользователя, чтобы не повторять поиск каждый раз
         *Нужно добавить код для реализации такой функции
         */
//        builder.setPositiveButton("Запомнить", new DialogInterface.OnClickListener() {
//                    @Override
//                    public void onClick(DialogInterface dialog, int which) {
//                        Log.d(TAG, "Нажато запомнить");
//                    }
//                }
//        );
        builder.setNegativeButton("Закрыть", null);
        builder.create();
        builder.show();
    }

//    private void defaultDevice (String MAC, boolean set) {
//
//    }

    //Проверяет разрешения на доступ к данным местоположения
    private void checkPermissionLocation() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
            //Android Studio ругается на ошибки. Тут всё нормально
            int check = checkSelfPermission("Manifest.permission.ACCESS_FINE_LOCATION");
            check += checkSelfPermission("Manifest.permission.ACCESS_COARSE_LOCATION");

            if (check != 0) {
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1002);
            }
        }
    }

    //Включаем Bluetooth
    private void enableBluetooth() {
        Log.d(TAG, "enableBluetooth()");
        if (!mBluetoothAdapter.isEnabled()) {
            Log.d(TAG, "enableBluetooth: Bluetooth выключен, пытаемся включить");
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(intent, REQ_ENABLE_BLUETOOTH);
        }
    }

    private void setMessage(String command) {
        byte[] buffer = command.getBytes();

        if (mOutputStream != null) {
            try {
                mOutputStream.write(buffer);
                mOutputStream.flush();
            }
            catch (IOException e) {
                connectionInterrupted();
                e.printStackTrace();
            }
        }
    }

    private void connectionInterrupted() {
        showToastMessage("Ошибка передачи");
        sound_enabled = false;
        setUnitVisibility(false);
        setControlButtonsVisibility(false);
        setMeasureVisibility(false);
        //setConnectButtonsVisibility(true);
        sensor.setText("Включите устройство и повторите соединение");
        sensor.setTextColor(getResources().getColor(R.color.rad_danger));
        sensor.setTextSize(36);
        average.setText("");
        battery.setText("");
    }

    private void startConnection(BluetoothDevice device) {
        if (device != null) {
            try {
                Method method = device.getClass().getMethod("createRfcommSocket", new Class[] {int.class});
                mBluetoothSocket = (BluetoothSocket) method.invoke(device, 1);
                mBluetoothSocket.connect();

                mOutputStream = mBluetoothSocket.getOutputStream();
                mInputStream = mBluetoothSocket.getInputStream();
                beginListenForData();

                showToastMessage("Подключено");

            }
            catch (Exception e) {
                showToastMessage("Ошибка");
                e.printStackTrace();
            }
        }
    }

    private void showToastMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        //Принудительно пытаемся включить bluetooth
        if (requestCode == REQ_ENABLE_BLUETOOTH) {
            if (!mBluetoothAdapter.isEnabled()) {
                Log.d(TAG, "Повторно пытаемся отправить запрос на включение bluetooth");
                enableBluetooth();
            }
        }
    }

    private void setUnitVisibility(boolean arg) {
        // Находим объект
        TextView unit = findViewById(R.id.textView2);
        // Скрываем объект
        if (arg) {
            unit.setVisibility(View.VISIBLE);
        }
        else {
            unit.setVisibility(View.INVISIBLE);
        }
    }

    private void setMeasureVisibility(boolean arg) {
        if (arg) {
            measHint.setVisibility(View.VISIBLE);
            measured.setVisibility(View.VISIBLE);
        }
        else {
            measHint.setVisibility(View.INVISIBLE);
            measured.setVisibility(View.INVISIBLE);
        }
    }

    private void setControlButtonsVisibility(boolean arg) {
        if (arg) {
            sound_control.setVisibility(View.VISIBLE);
            alert_switch.setVisibility(View.VISIBLE);
            meas_layout.setVisibility(View.VISIBLE);
            measured.setVisibility(View.VISIBLE);
        }
        else {
            sound_control.setVisibility(View.INVISIBLE);
            alert_switch.setVisibility(View.INVISIBLE);
            alert_lim.setVisibility(View.INVISIBLE);
            meas_layout.setVisibility(View.INVISIBLE);
            measured.setVisibility(View.INVISIBLE);
        }
    }

//    private void setConnectButtonsVisibility(boolean arg) {
//        if (arg) {
//            searchAgain.setVisibility(View.VISIBLE);
//        }
//        else {
//            searchAgain.setVisibility(View.INVISIBLE);
//        }
//    }

    private View.OnClickListener clickListener = new View.OnClickListener() {
        @SuppressLint("SetTextI18n")
        @Override
        public void onClick(View view) {
            String command = "";
            if (view.equals(sound_control)) {
                sound_enabled = !sound_enabled;
                command = sound_enabled ? "5" : "6"; //5 - выкл, 6 - вкл.
                setMessage(command);
                if (sound_enabled) {
                    sound_control.setTextColor(getResources().getColor(R.color.active));
                }
                else {
                    sound_control.setTextColor(getResources().getColor(R.color.inactive));
                }
                Log.d(TAG, "onClick: sound_enabled = " + sound_enabled);
            }

            if (view.equals(alert_switch)) {
                Log.d(TAG, "Смена настроек сигнализации");
                if (alert) {
                    alert_switch.setText("Тревоги выключены");
                    alert_lim.setVisibility(View.INVISIBLE);
                    alert_lim.setVisibility(View.INVISIBLE);
                    alert_switch.setTextColor(getResources().getColor(R.color.inactive));
                    alert = false;
                    command = "0";
                    setMessage(command);
                    //Передается команда на Arduino
                }
                else {
                    alert_switch.setText("Порог сигнализации");
                    if (first_lim_act) {
                        alert_lim_counter = 1;
                        active_lim_mkrph = 30;
                        command = "1";
                        setMessage(command);
                        if (unit_mksvph) {
                            alert_lim.setText("0.3");
                        }
                        else {
                            alert_lim.setText("30");
                        }
                        alert_lim.setVisibility(View.VISIBLE);
                        first_lim_act = false;
                        //Изменять цифры осторожно
                    }
                    alert_lim.setVisibility(View.VISIBLE);
                    alert_switch.setTextColor(getResources().getColor(R.color.active));
                    alert = true;
                    if (active_lim_mkrph == 30) { // переделать через switch/case
                        command = "0";
                    }
                    if (active_lim_mkrph == 60) {
                        command = "1";
                    }
                    if (active_lim_mkrph == 120) {
                        command = "2";
                    }
                    setMessage(command);
                }

            }
            if (view.equals(alert_lim)) {
                Log.d(TAG, "Нажаты цифры");
                if (alert_lim_counter >= 3) {
                    alert_lim_counter = 0;
                }

                if (alert_lim_counter == 0) { //переделать через switch/case
                    active_lim_mkrph = 30;
                    command = "1";
                    setMessage(command);
                    if (unit_mksvph) {
                        alert_lim.setText("0.3");
                    }
                    else {
                        alert_lim.setText("30");
                    }
                }
                if (alert_lim_counter == 1) { //переделать через switch/case
                    active_lim_mkrph = 60;
                    command = "2";
                    setMessage(command);
                    if (unit_mksvph) {
                        alert_lim.setText("0.6");
                    }
                    else {
                        alert_lim.setText("60");
                    }
                }
                if (alert_lim_counter == 2) { //переделать через switch/case
                    active_lim_mkrph = 120;
                    command = "3";
                    setMessage(command);
                    if (unit_mksvph) {
                        alert_lim.setText("1.2");
                    }
                    else {
                        alert_lim.setText("120");
                    }
                }
                alert_lim_counter++;
            }

            if (view.equals(meas_layout)) {
                Log.d(TAG, "Переход в режим измерения");
                measure_started = true;
                setMeasureVisibility(true);
                measHint.setText("Инициализация…");
                measured.setText("");
            }

            if (view.equals(unit)) {
                if (unit_mksvph) {
                    unit.setText("µR/h");
                    unit_mksvph = false;
                    //код ниже нужен для мгновенной конвертации уже полученных значений

                    //для верхнего поля
                    CharSequence curr_text_cs_s = sensor.getText();
                    String curr_text_s_s = String.valueOf(curr_text_cs_s);
                    double value_s = Double.parseDouble(curr_text_s_s);
                    value_s = value_s * 100;
                    sensor.setText(String.valueOf((int)value_s));

                    //для нижнего
                    CharSequence curr_text_cs_s_avg = average.getText();
                    String curr_text_s_s_avg = String.valueOf(curr_text_cs_s_avg);
                    double value_s_avg = Double.parseDouble(curr_text_s_s_avg);
                    value_s_avg = value_s_avg * 100;
                    average.setText(String.valueOf((int)value_s_avg));

                    //для порога
                    CharSequence curr_text_cs_s_ = alert_lim.getText();
                    String curr_text_s_s_ = String.valueOf(curr_text_cs_s_);
                    double value_s_ = Double.parseDouble(curr_text_s_s_);
                    value_s_ = value_s_ * 100;
                    alert_lim.setText(String.valueOf((int)value_s_));

                    //для поля измерения
                    try {
                        CharSequence curr_text_meas_cs_s = measured.getText();
                        String curr_text_meas_s_s = String.valueOf(curr_text_meas_cs_s);
                        double value_meas_s = Double.parseDouble(curr_text_meas_s_s);
                        value_meas_s = value_meas_s * 100;
                        measured.setText(String.valueOf((int)value_meas_s));
                    }
                    catch (NumberFormatException e) {

                    }


                }
                else {
                    unit.setText("µSv/h");
                    unit_mksvph = true;
                    CharSequence curr_text_cs_r = sensor.getText();
                    String curr_text_s_r = String.valueOf(curr_text_cs_r);
                    double value_r = Double.parseDouble(curr_text_s_r);
                    value_r = value_r / 100;
                    sensor.setText(String.valueOf(value_r));

                    CharSequence curr_text_cs_r_avg = average.getText();
                    String curr_text_s_r_avg = String.valueOf(curr_text_cs_r_avg);
                    double value_r_avg = Double.parseDouble(curr_text_s_r_avg);
                    value_r_avg = value_r_avg / 100;
                    average.setText(String.valueOf(value_r_avg));

                    CharSequence curr_text_cs_r_ = alert_lim.getText();
                    String curr_text_s_r_ = String.valueOf(curr_text_cs_r_);
                    double value_r_ = Double.parseDouble(curr_text_s_r_);
                    value_r_ = value_r_ / 100;
                    alert_lim.setText(String.valueOf(value_r_));

                    try {
                        CharSequence curr_text_meas_cs_r = measured.getText();
                        String curr_text_meas_s_r = String.valueOf(curr_text_meas_cs_r);
                        double value_meas_r = Double.parseDouble(curr_text_meas_s_r);
                        value_meas_r = value_meas_r / 100;
                        measured.setText(String.valueOf(value_meas_r));
                    }
                    catch (NumberFormatException e) {

                    }
                }

                Log.d(TAG, "Изменение единиц");
            }

        }
    };

    public static int stringToInt(String val, int defaultVal) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private void setRadColor(TextView tw, double mksvph_value) {
        if (mksvph_value < 0.3){
            tw.setTextColor(getResources().getColor(R.color.rad_normal));
        }
        if (mksvph_value >= 0.3 && mksvph_value < 1.2) {
            tw.setTextColor(getResources().getColor(R.color.rad_warning));
        }
        if (mksvph_value >= 1.2) {
            tw.setTextColor(getResources().getColor(R.color.rad_danger));
        }
    }

    void beginListenForData()
    {
        final Handler handler = new Handler();
        final byte delimiter = 10; //ASCII код новой строки

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {

                        int bytesAvailable = mInputStream.available();
                        if(bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];
                            mInputStream.read(packetBytes);
                            for(int i=0;i<bytesAvailable;i++)
                            {
                                byte b = packetBytes[i];
                                if(b == delimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;

                                    handler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            setUnitVisibility(true);
                                            setControlButtonsVisibility(true);
                                            //setConnectButtonsVisibility(false);

                                            String[] mas = data.split("\\|");

                                            try {
                                                mkrph = stringToInt(mas[0].replaceAll("[^0-9]",""), 0);
                                            }
                                            catch (ArrayIndexOutOfBoundsException e) {
                                                Log.d(TAG, "Array Err");
                                            }

                                            if (measure_started) {
                                                measHint.setText("Идёт измерение…");
                                                mkrph_meas.add(mkrph);
                                                counter_meas += 1;
                                                if (counter_meas == counter_meas_lim) {
                                                    double sum = 0;
                                                    for (int ii = 0; ii < counter_meas_lim; ii += 1) {
                                                        sum += mkrph_meas.get(ii);
                                                    }
                                                    double result_meas = sum/counter_meas;
                                                    if (unit_mksvph) {
                                                        double a = (double)Math.round((result_meas/100) * 100d) / 100d;
                                                        measured.setText(String.valueOf(a));
                                                    }
                                                    else {
                                                        measured.setText(String.valueOf((int)result_meas));
                                                    }
                                                    setRadColor(measured, (double)Math.round((result_meas/100) * 100d) / 100d);
                                                    measured.setTextSize(96);
                                                    measure_started = false;
                                                    counter_meas = 0;
                                                    mkrph_meas.clear();
                                                    measHint.setText("Измерение завершено");
                                                }
                                            }

                                            if (alert) {
                                                if (mkrph >= active_lim_mkrph) {
                                                    Log.d(TAG, "Превышение");
                                                    //Добавить присылание пуш-уведомления о превышении
                                                }
                                            }

                                            //Log.d(TAG, Arrays.toString(mas));
                                            double mksvph = (double) mkrph / 100;
                                            if (unit_mksvph) {
                                                sensor.setText(String.valueOf(mksvph));
                                            }
                                            else {
                                                sensor.setText(String.valueOf(mkrph));
                                            }
                                            setRadColor(sensor, mksvph);
                                            sensor.setTextSize(96);

                                            try {
                                                mkrph_avg = stringToInt(mas[1].replaceAll("[^0-9]",""), 0);
                                            }
                                            catch (ArrayIndexOutOfBoundsException e) {
                                                Log.d(TAG, "Array Err");
                                            }
                                            double mksvph_avg = (double) mkrph_avg / 100;
                                            if (unit_mksvph) {
                                                average.setText(String.valueOf(mksvph_avg));
                                            }
                                            else {
                                                average.setText(String.valueOf(mkrph_avg));
                                            }
                                            setRadColor(average, mksvph_avg);
                                            average.setTextSize(96);

                                            try {
                                                receivedVoltage = Double.parseDouble(mas[2].replaceAll("/[^0-9.]/",""));
                                            }
                                            catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                                                Log.d(TAG, "Err voltage format or array size");
                                            }
                                            if (receivedVoltage < 3.5) {
                                                battery.setVisibility(View.VISIBLE);
                                            }
                                            else {
                                                battery.setVisibility(View.INVISIBLE);
                                            }

                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    private AdapterView.OnItemClickListener itemOnClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
            BluetoothDevice device = mDevices.get(position);
//            address = device.getAddress();
//            Log.d(TAG, address);
            startConnection(device);
        }
    };


    private BroadcastReceiver mRecevier = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            //Начат поиск устройств
            if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_STARTED)) {
                Log.d(TAG, "onReceive: ACTION_DISCOVERY_STARTED");

                showToastMessage("Начат поиск устройств");

                mProgressDialog = ProgressDialog.show(MainActivity.this, "Поиск устройств", " Пожалуйста, подождите...");
            }

            //Поиск устройств завершен
            if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)) {
                Log.d(TAG, "onReceive: ACTION_DISCOVERY_FINISHED");
                showToastMessage("Поиск устройств завершен");

                mProgressDialog.dismiss();

                showListDevices();
            }

            //Если найдено новое устройство
            if (action.equals(BluetoothDevice.ACTION_FOUND)) {
                Log.d(TAG, "onReceive: ACTION_FOUND");
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    if (!mDevices.contains(device))
                        mDeviceListAdapter.add(device);
                }
            }
        }
    };
}
