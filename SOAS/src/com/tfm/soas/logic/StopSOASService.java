package com.tfm.soas.logic;

import com.tfm.soas.context.AppContext;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.widget.Toast;

/**
 * Servicio que permite detener el sistema de adelantamientos seguros (SOAS) de
 * forma correcta.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 27-05-2014
 */
public class StopSOASService extends Service {

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Inicializa el servicio.
	 */
	@Override
	public void onCreate() {
		// Se detiene el sistema finalizando los servicios Cliente y Servidor.
		stopService(new Intent(getApplicationContext(), SOASClient.class));
		stopService(new Intent(getApplicationContext(), SOASServer.class));

		// Se guarda en las preferencias no volatiles el estado.
		SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
				Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt("state", 0); // PARADO
		editor.commit();

		// Se indica al usuario que el sistema se ha detenido.
		if (AppContext.toast == null) { // El toast fue destruido.
			AppContext.toast = Toast.makeText(getApplicationContext(), "",
					Toast.LENGTH_SHORT);
		}
		AppContext.toast.setText("SOAS OFF");
		AppContext.toast.show();

		// Si la actividad pricipal esta en primer plano se indica que
		// actualice su interfaz.
		sendBroadcast(new Intent("stop_SOAS"));

		// Se detiene el servicio.
		stopSelf();
	}

	/**
	 * Devuelve el canal de comunicacion al servicio.
	 * 
	 * @param intent
	 *            El Intent que se conecto al servicio.
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

} // Fin clase 'StopSOASService'
