package com.tfm.soas.logic;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;

import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * Servicio encargado de proporcionar la localizacion del dispositivo a los
 * servicios Cliente (SOASClient) y Servidor (SOASServer).
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 15-07-2014
 */
public class LocationService extends Service implements
		GooglePlayServicesClient.ConnectionCallbacks,
		GooglePlayServicesClient.OnConnectionFailedListener, LocationListener {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	private static final long UPDATE_INTERVAL = 1500; // 1,5 sg.
	private static final long FASTEST_INTERVAL = 1000; // 1 sg.
	private static final String TAG = "LocationService";

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private final IBinder binder = new LocalBinder(); // Interfaz acceso.
	private LocationClient locationClient = null; // Acceso localizacion.
	private LocationRequest locationRequest = null; // Peticion localizacion.
	private Location lastLocation = null; // Ultima localizacion.
	private boolean offRequested = false; // Desconexion solicitada.

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/*---------*
	 | SERVICE |
	 *---------*/
	/**
	 * Inicializacion del servicio.
	 */
	@Override
	public void onCreate() {
		super.onCreate();

		// Se crea y conecta el LocationClient a traves del cual solicitar la
		// localizacion del dispositivo.
		locationClient = new LocationClient(this, this, this);
		locationClient.connect();

		// Se crea el objeto de peticion de localizaciones.
		locationRequest = LocationRequest.create();
		locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
		locationRequest.setInterval(UPDATE_INTERVAL);
		locationRequest.setFastestInterval(FASTEST_INTERVAL);
	}

	/**
	 * Detencion del servicio.
	 */
	@Override
	public void onDestroy() {
		super.onDestroy();

		// Se eliminan las actualizaciones periodicas.
		if (locationClient.isConnected()) {
			locationClient.removeLocationUpdates(this);
		}

		// Se desconecta el LocationClient.
		offRequested = true;
		locationClient.disconnect();
	}

	/**
	 * Devuelve el canal de comunicacion al servicio.
	 * 
	 * @param intent
	 *            El Intent que se conecto al servicio
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	/**
	 * Metodo empleado por los clientes del servicio para obtener la ulima
	 * localizacion conocida del dispositivo.
	 * 
	 * @return Localizacion mas reciente
	 */
	public Location getLastLocation() {
		return lastLocation;
	}

	/*-------------------*
	 | LOCATION LISTENER |
	 *-------------------*/
	/**
	 * Este metodo es llamado cuando la solicitud de conexion del cliente se
	 * realiza correctamente.
	 */
	@Override
	public void onConnected(Bundle bundle) {
		Log.d(TAG, "(LS) Client connected.");

		// Se solicitan las actualizaciones periodicas.
		locationClient.requestLocationUpdates(locationRequest, this);
	}

	/**
	 * Este metodo es llamado cuando la solicitud de conexion del cliente no ha
	 * terminado correctamente.
	 */
	@Override
	public void onConnectionFailed(ConnectionResult result) {
		Log.d(TAG, "(LS) Client connection fail. Please re-connect.");

		// Se intenta conectar de nuevo.
		locationClient.connect();
	}

	/**
	 * Este metodo es llamado cuando el cliente se desconecta.
	 */
	@Override
	public void onDisconnected() {
		Log.d(TAG, "(LS) Location Services - Client disconnected.");

		// Se intenta conectar de nuevo si la desconexion no fue solicitada.
		if (!offRequested) {
			locationClient.connect();
		}
	}

	/**
	 * Este metodo es llamado cuando la localizacion del dispositivo a cambiado.
	 */
	@Override
	public void onLocationChanged(Location location) {
		// Se actualiza la ultima localizacion conocida si la que se acaba de
		// recibir es mas actual que la que ya se tenia.
		boolean update;
		if (lastLocation == null) {
			update = true;
		} else if (location.getTime() > lastLocation.getTime()) {
			update = true;
		} else {
			update = false;
		}
		if (update) {
			lastLocation = location;

			String msg = "(LS) Updated Location: "
					+ Double.toString(location.getLatitude()) + ","
					+ Double.toString(location.getLongitude()) + " - Time: "
					+ Long.toString(location.getTime()) + " - Accuracy: "
					+ Float.toString(location.getAccuracy());
			Log.d(TAG, msg);
		}
	}

	/*--------------------------------------------------------*/
	/* /////////////////// CLASES INTERNAS ////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Clase empleada por los clientes del servicio LocationService (SOASClient,
	 * SOASServer) como interfaz de acceso a sus metodos publicos.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 16-07-2014
	 */
	public class LocalBinder extends Binder {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Devuelve una instancia de LocationService a traves de la cual los
		 * clientes acceden a los metodos publicos.
		 * 
		 * @return Servicio de localizacion
		 */
		LocationService getService() {
			return LocationService.this;
		}

	} // Fin clase interna 'LocalBinder'

} // Fin clase 'LocationService'
