package com.tfm.soas.view_controller;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.tfm.soas.R;
import com.tfm.soas.context.AppContext;
import com.tfm.soas.logic.SOASClient;
import com.tfm.soas.logic.SOASServer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CompoundButton.OnCheckedChangeListener;

/**
 * Actividad lanzadora de la aplicacion. Contiene un boton interruptor de dos
 * posiciones que permite activar o desactivar el sistema de adelantamientos
 * seguros (SOAS) proporcionado por la App. Además proporciona acceso a las
 * otras actividades de la vista: Ajustes, Logs y Creditos.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 27-05-2014
 */
public class MainActivity extends Activity {

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private CompoundButton switchButton = null; // ON-OFF button.
	private ImageButton settingsButton = null; // Settings button.
	private ImageButton logsButton = null; // Logs button.
	private boolean isSOASOn = false; // ON Flag.
	private StopSOASReceiver stopReceiver = null; // Receptor parada SOAS.
	private int connErrorType = -1; // Tipo error conectividad.

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Inicializa la actividad.
	 * 
	 * @param savedInstanceState
	 *            Si la actividad esta siendo re-inicializada este Bundle
	 *            contiene informacion reciente acerca de su anterior estado
	 */
	@SuppressLint("ShowToast")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Se carga la interfaz de la actividad.
		setContentView(R.layout.activity_main);

		// Se obtiene la direccion IP del dispositivo y se muestra en la
		// interfaz de la actividad.
		String ip = AppContext.getIPAddress(true);
		TextView field = (TextView) findViewById(R.id.ip);
		field.setText(ip);

		// Se recupera una referencia a los botones de ajustes y de acceso a
		// logs, y se les asigna un oyente.
		settingsButton = (ImageButton) findViewById(R.id.settings_button);
		logsButton = (ImageButton) findViewById(R.id.logs_button);
		MainBtnListener bListener = new MainBtnListener();
		settingsButton.setOnClickListener(bListener);
		logsButton.setOnClickListener(bListener);

		// Se instancia el BroadcastReceiver que es avisado cuando el sistema es
		// detenido desde la notificacion de la barra principal.
		stopReceiver = new StopSOASReceiver();

		// Se instancia el toast para mostrar mensajes al usuario.
		AppContext.toast = Toast.makeText(getApplicationContext(), "",
				Toast.LENGTH_SHORT);

		// Se solicitan las dimensiones del dispositivo para que asi queden
		// guardadas en las preferencias.
		AppContext.getScreenDimensions(getApplicationContext());
	}

	/**
	 * Permite configurar los elementos que componen la actividad antes de que
	 * esta pase a primer plano.
	 */
	@Override
	protected void onResume() {
		super.onResume();

		// Se valida la disponibilidad de Google Play Services en el
		// dispositivo.
		checkServices();

		// Se recupera una referencia al boton interruptor, se establece su
		// estado y se le asigna un oyente.
		switchButton = (CompoundButton) findViewById(R.id.enable_button);
		SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
				Context.MODE_PRIVATE);
		if (prefs.getInt("state", 0) == 0) { // OFF
			switchButton.setChecked(false);
			isSOASOn = false;
		} else { // ON
			switchButton.setChecked(true);
			isSOASOn = true;
		}
		switchButton.setOnCheckedChangeListener(new SwitchListener());

		// Se registra el BroadcastReceiver que detecta la parada del sistema.
		registerReceiver(stopReceiver, new IntentFilter("stop_SOAS"));
	}

	/**
	 * Permite liberar los recursos utilizados por la actividad antes de que
	 * esta pase a segundo plano.
	 */
	@Override
	protected void onPause() {
		super.onPause();

		// Se elimina el oyente del boton interruptor.
		switchButton.setOnCheckedChangeListener(null);

		// Se elimina el BroadcastReceiver que se registro para detectar la
		// parada del sistema.
		unregisterReceiver(stopReceiver);
	}

	/**
	 * Permite inicializar el menu de la actividad, el cual permite acceder a la
	 * pantalla de creditos de la aplicacion.
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	/**
	 * Permite detectar las pulsaciones sobre los elementos del menu y llevar a
	 * cabo las acciones oportunas.
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Se evalua que elemento del menu fue seleccionado.
		switch (item.getItemId()) {
		case R.id.menu_credits: { // CREDITOS.
			// Se arranca la actividad de creditos.
			startActivity(new Intent(MainActivity.this, CreditsActivity.class));
			return true;
		}
		}
		return false;
	}

	/**
	 * Permite determinar si el dispositivo donde se ejecuta la app cuenta con
	 * una version valida de Google Play Services. En caso negativo muestra un
	 * dialogo al usuario, el cual contiene un enlace a la pagina de
	 * instalacion.
	 * 
	 * @return True/False
	 */
	private void checkServices() {
		// Estado de los servicios.
		int isAvailable = GooglePlayServicesUtil
				.isGooglePlayServicesAvailable(this);

		switch (isAvailable) {
		case ConnectionResult.SUCCESS:
			break;
		case ConnectionResult.SERVICE_MISSING:
		case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED:
		case ConnectionResult.SERVICE_DISABLED:
			Dialog dialog = GooglePlayServicesUtil.getErrorDialog(isAvailable,
					this, -1);
			if (dialog != null) {
				dialog.show();
			}
			break;
		default:
			AppContext.toast
					.setText("Can't start the app because of the state of Google Play Services");
			AppContext.toast.show();
		}
	}

	/*--------------------------------------------------------*/
	/* /////////////////// CLASES INTERNAS ////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Oyente encargado de gestionar los cambios de estado del boton interruptor
	 * que activa/desactiva la App.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 27-05-2014
	 */
	private class SwitchListener implements OnCheckedChangeListener {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Permite comprobar el estado de la conectividad Wi-Fi y GPS del
		 * dispositivo. En el caso de que se encuentren desconectadas lanza un
		 * aviso al usuario para que proceda a activarlas.
		 * 
		 * @return OK-True / Not OK-False
		 */
		private boolean isConnectivityOk() {
			// Tipo error conectividad: (-1) Sin Error / (0) Wi-Fi / (1) GPS /
			// (2) Wi-Fi y GPS
			connErrorType = -1;

			// Validacion Wi-Fi.
			ConnectivityManager connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
			NetworkInfo mWifi = connManager
					.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
			if (!mWifi.isConnected()) {
				connErrorType = 0;
			}

			// Validacion GPS.
			LocationManager locManager = (LocationManager) getSystemService(LOCATION_SERVICE);
			if (!locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
				if (connErrorType == -1) { // Wi-Fi OK.
					connErrorType = 1;
				} else if (connErrorType == 0) { // Wi-Fi no OK.
					connErrorType = 2;
				}
			}

			if (connErrorType == -1) { // SIN ERROR.
				return true;
			} else { // CON ERROR.
				return false;
			}
		}

		/**
		 * Lanza un dialogo para informar al usuario de que la conectividad WiFi
		 * se encuentra inhabilitada, y le proporciona la posiblidad de
		 * habilitarla.
		 * 
		 * @param message
		 *            Mensaje a mostrar al usario.
		 */
		private void buildAlertMessageNoWiFi() {
			final AlertDialog.Builder builder = new AlertDialog.Builder(
					MainActivity.this);
			builder.setMessage(
					"Wi-Fi is disabled in your device, enable it to run the app properly.")
					.setCancelable(false)
					.setPositiveButton("Enable",
							new DialogInterface.OnClickListener() { // ACTIVAR.
								public void onClick(
										final DialogInterface dialog,
										final int id) {
									startActivity(new Intent(
											Settings.ACTION_WIFI_SETTINGS));
								}
							})
					.setNegativeButton("Cancel",
							new DialogInterface.OnClickListener() { // NO_ACTIVAR.
								public void onClick(
										final DialogInterface dialog,
										final int id) {
									// Se cierra el dialogo.
									dialog.cancel();
								}
							});
			final AlertDialog alert = builder.create();
			alert.show();
		}

		/**
		 * Lanza un dialogo para informar al usuario de que la conectividad GPS
		 * se encuentra inhabilitada, y le proporciona la posiblidad de
		 * habilitarla.
		 * 
		 * @param message
		 *            Mensaje a mostrar al usario.
		 */
		private void buildAlertMessageNoGPS() {
			final AlertDialog.Builder builder = new AlertDialog.Builder(
					MainActivity.this);
			builder.setMessage(
					"GPS is disabled in your device, enable it to run the app properly.")
					.setCancelable(false)
					.setPositiveButton("Enable",
							new DialogInterface.OnClickListener() { // ACTIVAR.
								public void onClick(
										final DialogInterface dialog,
										final int id) {
									startActivity(new Intent(
											android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));

								}
							})
					.setNegativeButton("Cancel",
							new DialogInterface.OnClickListener() { // NO_ACTIVAR.
								public void onClick(
										final DialogInterface dialog,
										final int id) {
									// Se cierra el dialogo.
									dialog.cancel();
								}
							});
			final AlertDialog alert = builder.create();
			alert.show();
		}

		/**
		 * Se ejecuta cuando el botón interruptor cambia de estado.
		 * 
		 * @param buttonView
		 *            CompoundButton que ha cambiado de estado
		 * @param isChecked
		 *            True-ON / False-OFF
		 */
		@Override
		public void onCheckedChanged(CompoundButton buttonView,
				boolean isChecked) {
			int state;
			if (isChecked) { // ON.
				if (isConnectivityOk()) {
					state = 1;
					isSOASOn = true;

					// Se arranca el sistema.
					startService(new Intent(getApplicationContext(),
							SOASClient.class));
					startService(new Intent(getApplicationContext(),
							SOASServer.class));

					// Se muestra el mensaje con la accion seleccionada.
					AppContext.toast
							.setText("SOAS ON - Don't turn off your device");
					AppContext.toast.show();
				} else {
					state = 0; // OFF.
					isSOASOn = false;

					// Se muestra el dialogo de error al usuario.
					switch (connErrorType) {
					case 0: // Wi-Fi.
						buildAlertMessageNoWiFi();
						break;
					case 1: // GPS.
						buildAlertMessageNoGPS();
						break;
					case 2: // Wi-Fi y GPS
						buildAlertMessageNoWiFi();
						buildAlertMessageNoGPS();
						break;
					}

					// Boton interruptor OFF.
					switchButton.setChecked(false);
				}
			} else { // OFF.
				state = 0;
				isSOASOn = false;

				// Se detiene el sistema.
				stopService(new Intent(getApplicationContext(),
						SOASClient.class));
				stopService(new Intent(getApplicationContext(),
						SOASServer.class));

				// Se muestra el mensaje con la accion seleccionada.
				AppContext.toast.setText("SOAS OFF");
				AppContext.toast.show();
			}

			// Se guarda en las preferencias no volatiles el estado.
			SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
					Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = prefs.edit();
			editor.putInt("state", state);
			editor.commit();
		}

	} // Fin clase interna 'SwitchListener'

	/**
	 * Oyente encargado de gestionar las pulsaciones sobre los botones de la
	 * actividad.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 25-08-2013
	 */
	private class MainBtnListener implements View.OnClickListener {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Se ejecuta cuando alguno de los elementos que gestiona es pulsado.
		 */
		@Override
		public void onClick(View v) {
			// Se evalua que boton de la pantalla fue pulsado y se actua
			// consecuentemente.
			switch (v.getId()) {
			case R.id.settings_button: { // AJUSTES.
				if (isSOASOn) { // SOAS ON.
					AppContext.toast.setText("Stop SOAS to change settings");
					AppContext.toast.show();
				} else { // SOAS OFF.
					// Se arranca la actividad de ajustes.
					startActivity(new Intent(MainActivity.this,
							SettingsActivity.class));
				}
				break;
			}
			case R.id.logs_button: { // LOGS.
				if (isSOASOn) { // SOAS ON.
					AppContext.toast.setText("Stop SOAS to see the logs");
					AppContext.toast.show();
				} else { // SOAS OFF.
					// Se arranca la actividad de visualizacion de logs.
					startActivity(new Intent(MainActivity.this,
							LogsActivity.class));
				}
				break;
			}
			}
		}

	} // Fin clase interna 'MainBtnListener'

	/**
	 * BroadcastReceiver que es notificado de la detencion del sistema para que
	 * actualice la interfaz de la actividad.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 29-05-2014
	 */
	private class StopSOASReceiver extends BroadcastReceiver {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Se ejecuta cuando el BroadcastReceiver recibe un Intent broadcast.
		 * 
		 * @param context
		 *            Contexto en el que se esta ejecutando el Receiver
		 * @param intent
		 *            Intent que se ha recibido
		 */
		@Override
		public void onReceive(Context context, Intent intent) {
			// Se modifica el estado del boton interruptor.
			switchButton.setChecked(false);
			isSOASOn = false;
		}

	} // Fin clase interna 'StopSOASReceiver'

} // Fin clase 'MainActivity'
