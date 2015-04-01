package com.tfm.soas.logic;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;

import com.tfm.soas.context.AppContext;
import com.tfm.soas.logic.LocationService.LocalBinder;
import com.tfm.soas.logic.SOASMessage.MessageType;
import com.tfm.soas.view_controller.RTSPPlayerActivity;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

/**
 * Servicio que implementa el comportamiento CLIENTE del sistema. Se encargara
 * de:
 * 
 * 1- Escuchar los anuncios emitidos por los dispositivos servidor de otros
 * vehiculos cercanos, evaluando sus posiciones para saber si alguno de ellos es
 * el vehiculo situado justo delante.
 * 
 * 2- Solicitar el servicio de streaming RTSP al dispositivo servidor ubicado
 * inmediatamente delante, manteniendose a la espera de la correspondiente
 * respuesta.
 * 
 * 3- Conectarse al servicio de streaming RTSP tras recibir la confirmacion del
 * dispositivo servidor.
 * 
 * 4- Cerrar correctamente la sesion de streaming una vez que no sea necesaria.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 27-05-2014
 */
public class SOASClient extends Service {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	private static enum ClientState {
		INI, LISTEN, REQUEST, PLAY, END
	}

	private static final int StartStreaming = 1;
	private static final int StopStreaming = 2;
	private static final String TAG = "SOASClient";
	private static final String TAG_2 = "Client Validation";

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private ClientState state = ClientState.INI; // Estado Cliente.
	private volatile ClientDiagramThread dThread = null; // Hilo diagrama de estados.
	private volatile ClientSendThread sThread = null; // Hilo emisor de mensajes.
	private volatile ClientReceiveThread rThread = null; // Hilo receptor de mensajes.
	private SOASMessageQueue messageQueue = null; // Cola de mensajes.
	private LocationService lService = null; // Acceso localizacion.
	private LocationServiceConn lServiceConnection = null; // Gestor conexion.
	private boolean boundLService = false; // Conectado a servicio localizacion.
	private volatile Location[] lastLocation = { null, null }; // Vector 2D.
	private Handler handler = null; // Comunicacion con el hilo principal.
	private StopPlayReceiver stopReceiver = null; // Receptor fin reproduccion.
	private Boolean isPlaying = false; // Flag reproduccion streaming RTSP.
	private WakeLock wl = null; // WaveLock CPU ON.

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
	@SuppressWarnings("deprecation")
	public void onCreate() {
		super.onCreate();

		// Se añade un WaveLock parcial que mantenga la CPU activa para
		// garantizar que el cliente no se detiene.
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
				"Client WaveLock");
		wl.acquire();

		// Se crea el gestor de mensajes que permite la comunicacion entre los
		// hilos secundarios y el hilo principal del servicio.
		handler = new ClientHandler(this);

		// Se instancia y registra el BroadcastReceiver que es avisado del
		// cierre del reproductor RTSP.
		stopReceiver = new StopPlayReceiver();
		registerReceiver(stopReceiver, new IntentFilter("stop_Play"));

		// Se establece conexion con LocationService, a traves del cual
		// solicitar la localizacion del dispositivo.
		Intent intent = new Intent(this, LocationService.class);
		lServiceConnection = new LocationServiceConn();
		bindService(intent, lServiceConnection, Context.BIND_AUTO_CREATE);

		// Se arrancan los hilos que definen el comportamiento del cliente.
		launchThreads();
	}

	/**
	 * Detencion del servicio.
	 */
	@Override
	public void onDestroy() {
		// Se detiene el reproductor RTSP en caso de que siga ejecutandose.
		stopStreaming();

		// Se detiene los hilos que definen el comportamiento del cliente.
		stopThreads();

		// Se cierra la conexion con el servicio de localizacion.
		if (boundLService) {
			unbindService(lServiceConnection);
			boundLService = false;
		}

		// Se elimina el BroadcastReceiver que se registro para detectar el
		// cierre del reproductor RTSP.
		unregisterReceiver(stopReceiver);

		// Se liberan el WaveLock.
		wl.release();
	}

	/**
	 * Lanza a ejecucion el hilo receptor de mensajes, el hilo emisor de
	 * mensajes y el hilo que implementa el diagrama de estados.
	 */
	private void launchThreads() {
		// Se actualiza el estado antes de arrancar los hilos.
		state = ClientState.LISTEN;

		// Se arranca el hilo receptor de mensajes, y se crea la cola de
		// mensajes donde los almacenara.
		messageQueue = new SOASMessageQueue();
		rThread = new ClientReceiveThread();
		rThread.start();

		// Se arranca el hilo emisor de mensajes.
		sThread = new ClientSendThread();
		sThread.start();

		// Se arranca el hilo que implementa el diagrama de estados.
		dThread = new ClientDiagramThread();
		dThread.start();
	}

	/**
	 * Interrumpe el hilo receptor de mensajes, el hilo emisor de mensajes y el
	 * hilo que implementa el diagrama de estados.
	 */
	private void stopThreads() {
		// Se interrumpe el hilo receptor de mensajes.
		rThread.stopThread();

		// Se interrumpe el hilo emisor de mensajes.
		sThread.stopThread();

		// Se interrumpe el hilo que implementa el diagrama de estados.
		dThread.stopThread();
	}

	/**
	 * Permite iniciar la sesion de streaming conectandose al servidor RTSP.
	 */
	private void startStreaming(String ip, String port) {
		// Se arranca la actividad de reproduccion de video RTSP.
		String rtsp_url = "rtsp://" + ip + ":" + port;
		Intent intent = new Intent(SOASClient.this, RTSPPlayerActivity.class);
		intent.putExtra("rtsp_server_url", rtsp_url);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
				| Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		startActivity(intent);
	}

	/**
	 * Permite finalizar la sesion de streaming desconectandose del servidor
	 * RTSP.
	 */
	private void stopStreaming() {
		// Se detiene la reproduccion del video RTSP.
		sendBroadcast(new Intent("player_Receiver"));
	}

	/**
	 * Devuelve la localizacion actual del dispositivo a traves de un vector de
	 * dos dimensiones AB.
	 * 
	 * @return Localizacion - Punto A: Elementos 0,1 / Punto B: Elementos 2,3
	 */
	private synchronized double[] getLocation() {
		// Valor por defecto cuando no hay disponible un vector de localizacion
		// valido.
		double[] cLocation = { 0, 0, 0, 0 };

		// Se solicita una nueva localizacion.
		Location location = null;
		if (boundLService) {
			location = lService.getLastLocation();
		}

		if (location != null) { // NUEVA LOCALIZACION DISPONIBLE.
			if ((lastLocation[0] == null) && (lastLocation[1] == null)) { // PRIMER-PUNTO.
				lastLocation[0] = new Location("");
				lastLocation[1] = new Location("");
				lastLocation[0].setLatitude(location.getLatitude());
				lastLocation[0].setLongitude(location.getLongitude());
				lastLocation[0].setTime(location.getTime());
				lastLocation[1].setLatitude(location.getLatitude());
				lastLocation[1].setLongitude(location.getLongitude());
				lastLocation[1].setTime(location.getTime());
			} else if ((location.getLatitude() == lastLocation[1].getLatitude())
					&& (location.getLongitude() == lastLocation[1]
							.getLongitude())) { // MISMO-ULTIMO-PUNTO.
				lastLocation[1].setLatitude(location.getLatitude());
				lastLocation[1].setLongitude(location.getLongitude());
				lastLocation[1].setTime(location.getTime());
			} else { // ACTUALIZAR-VECTOR.
				lastLocation[0].setLatitude(lastLocation[1].getLatitude());
				lastLocation[0].setLongitude(lastLocation[1].getLongitude());
				lastLocation[0].setTime(lastLocation[1].getTime());
				lastLocation[1].setLatitude(location.getLatitude());
				lastLocation[1].setLongitude(location.getLongitude());
				lastLocation[1].setTime(location.getTime());
			}
		}

		// Se comprueba si se posee un vector de direccion y posicion.
		if ((lastLocation[0] != null) && (lastLocation[1] != null)) { // VECTOR-DISPONIBLE.
			// Se valida la antiguedad de la localizacion.
			long timeDiff = System.currentTimeMillis()
					- lastLocation[1].getTime();
			if (timeDiff < 5000) { // VALIDO.
				cLocation[0] = lastLocation[0].getLongitude();
				cLocation[1] = lastLocation[0].getLatitude();
				cLocation[2] = lastLocation[1].getLongitude();
				cLocation[3] = lastLocation[1].getLatitude();
			} else { // CADUCADO.
				lastLocation[0] = null;
				lastLocation[1] = null;
			}
		}

		return cLocation;
	}

	/**
	 * Devuelve el canal de comunicacion al servicio.
	 * 
	 * @param intent
	 *            El Intent que se conecto al servicio
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	/*--------------------------------------------------------*/
	/* /////////////////// CLASES INTERNAS ////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Clase que permite monitorizar el estado del servicio de localizacion
	 * LocationService.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 16-07-2014
	 */
	public class LocationServiceConn implements ServiceConnection {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Este metodo es llamado cuando la conexion con el servicio se ha
		 * establecido.
		 */
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// Conexion establecida con el servicio. Se obtiene una instancia de
			// este para llamar a sus metodos publicos
			LocalBinder binder = (LocalBinder) service;
			lService = binder.getService();
			boundLService = true;
		}

		/**
		 * Este metodo es llamado cuando la conexion con el servicio se ha
		 * perdido.
		 */
		@Override
		public void onServiceDisconnected(ComponentName name) {
			boundLService = false;
		}

	} // Fin clase interna 'LocationServiceConn'

	/**
	 * BroadcastReceiver que es notificado del cierre del reproductor RTSP por
	 * parte del usuario, y que por tanto actualiza el flag de reproduccion de
	 * streaming para finalizar la sesion de streaming correctamente.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 07-07-2014
	 */
	private class StopPlayReceiver extends BroadcastReceiver {

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
			// Se actualiza el flag de reproduccion de streaming RTSP.
			synchronized (isPlaying) {
				isPlaying = false;
			}
		}

	} // Fin clase interna 'StopPlayReceiver'

	/**
	 * Handler encargado de recibir, evaluar y gestionar adecuadamente los
	 * mensajes emitidos desde los hilos secundarios hacia el hilo principal del
	 * servicio.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 06-07-2014
	 * 
	 */
	private class ClientHandler extends Handler {

		/*-----------*/
		/* ATRIBUTOS */
		/*-----------*/
		private WeakReference<SOASClient> owner;

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Constructor para instancias de la clase ClientHandler.
		 * 
		 * @param owner
		 *            Propietario donde se originan los eventos
		 */
		public ClientHandler(SOASClient owner) {
			this.owner = new WeakReference<SOASClient>(owner);
		}

		/**
		 * Este metodo permite gestionar los distintos eventos que recibe el
		 * handler.
		 * 
		 * @param msg
		 *            Mensaje con info del evento ocurrido
		 */
		@Override
		public void handleMessage(Message msg) {
			SOASClient clientService = owner.get();

			// Se evalua el tipo de mensaje.
			if (msg.what == StartStreaming) { // Arrancar el streaming.
				Bundle bundle = msg.getData();
				clientService.startStreaming(bundle.getString("ip"),
						bundle.getString("port"));
				return;
			} else if (msg.what == StopStreaming) { // Parar el streaming.
				clientService.stopStreaming();
			}
		}

	} // Fin clase interna 'ClientHandler'

	/**
	 * Hilo emisor de mensajes.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 08-06-2014
	 */
	private class ClientSendThread extends Thread {

		/*-----------*/
		/* ATRIBUTOS */
		/*-----------*/
		private DatagramSocket sendSocket = null; // Socket para envios.
		private DatagramPacket packet = null; // Paquete a enviar.

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Constructor para instancias de la clase SendThread.
		 */
		public ClientSendThread() {
			try {
				// Se arranca el socket UDP de envio de mensajes.
				sendSocket = new DatagramSocket();
			} catch (SocketException e) {
				Log.d(TAG, "(C) Error opening Send Socket: " + e.getMessage());
				e.printStackTrace();

				// Se interrumpe el hilo.
				Thread.currentThread().interrupt();

				// Se detiene el sistema.
				startService(new Intent(SOASClient.this, StopSOASService.class));
			}
		}

		/**
		 * Permite establecer el paquete UDP que se desea enviar.
		 * 
		 * @param packet
		 *            Paquete UDP
		 */
		public void setPacket(DatagramPacket packet) {
			this.packet = packet;
		}

		/**
		 * Permite detener la ejecucion del hilo emisor de mensajes.
		 */
		public void stopThread() {
			sThread.interrupt();
			sendSocket.close();
		}

		/**
		 * Implementa el comportamiento del hilo emisor de mensajes.
		 */
		@Override
		public void run() {
			Thread thisThread = Thread.currentThread();
			if (!thisThread.isInterrupted()) {
				try {
					if (packet != null) {
						sendSocket.send(packet);
						packet = null;
					}
				} catch (IOException e) {
					Log.d(TAG, "(C) Error sending message: " + e.getMessage());
					e.printStackTrace();
				}
			}
		}

	} // Fin clase interna 'ClientSendThread'

	/**
	 * Hilo receptor de mensajes, encargado de escuchar, filtrar y encolar los
	 * mensajes enviados por los dispositivos servidor.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 02-06-2014
	 */
	private class ClientReceiveThread extends Thread {

		/*-----------*/
		/* ATRIBUTOS */
		/*-----------*/
		private DatagramSocket receiveSocket = null; // Socket para recepciones.

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Constructor para instancias de la clase ReceiveThread.
		 */
		public ClientReceiveThread() {
			try {
				// Se arranca el socket UDP de recepcion de mensajes.
				receiveSocket = new DatagramSocket(
						AppContext.RECEIVE_CLIENT_PORT);
			} catch (SocketException e) {
				Log.d(TAG,
						"(C) Error opening Receive Socket: " + e.getMessage());
				e.printStackTrace();

				// Se interrumpe el hilo.
				Thread.currentThread().interrupt();

				// Se detiene el sistema.
				startService(new Intent(SOASClient.this, StopSOASService.class));
			}
		}

		/**
		 * Permite detener la ejecucion del hilo receptor de mensajes.
		 */
		public void stopThread() {
			rThread.interrupt();
			receiveSocket.close();
			rThread = null;
		}

		/**
		 * Implementa el comportamiento del hilo receptor de mensajes.
		 */
		@Override
		public void run() {
			// Se crea el Datagrama UDP donde se almacenaran temporalmente los
			// mensajes recibidos.
			byte[] recvBuf = new byte[1000];
			DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);

			// Variable que guarda el estado actual accediendo de manera
			// sincronizada a la variable compartida que guarda el estado del
			// cliente.
			ClientState tmp_state;

			// Lista que almacena las IPs de los servidores de los que se ha
			// recibido un mensaje HELLO durante el estado LISTEN, y en el
			// instante en que se recibieron. Se empleara para descartar los
			// mensajes HELLO repetidos en un periodo breve de tiempo (2,5 sg).
			HashMap<String, Long> serversHELLO = new HashMap<String, Long>();

			// Mientras no se interrumpa el hilo se escuchan mensajes.
			Thread thisThread = Thread.currentThread();
			while ((!thisThread.isInterrupted()) && (thisThread == rThread)) {
				try {
					// Se espera la recepcion de un mensaje.
					receiveSocket.receive(packet);

					// Se reconstruye el mensaje recibido.
					SOASMessage message = AppContext
							.deserializeMessage(recvBuf);

					// Se comprueba que el emisor no sea el propio dispositivo.
					// Mensajes Broadcast.
					String localIP = AppContext.getIPAddress(true);
					if (!localIP.equalsIgnoreCase(message.getIp())) {
						// FILTRADO ESTADO-TIPO MENSAJE.
						// Se encolara el mensaje si es de interes para el
						// estado actual.
						boolean enqueue = false;
						synchronized (state) {
							tmp_state = state;
						}
						switch (tmp_state) {
						case LISTEN:
							if (message.getType() == MessageType.HELLO) {
								// Si no es un HELLO de un servidor ocupado y si
								// no se han recibido mas anuncios del mismo
								// servidor se marca para encolar.
								if (!message.getIp()
										.equalsIgnoreCase("X.X.X.X")) {
									String ip = message.getIp();
									boolean repeated = false;
									long now = System.currentTimeMillis();
									if (serversHELLO.containsKey(ip)) {
										long timeDiff = now
												- serversHELLO.get(ip);
										if (timeDiff < 2500) {
											repeated = true;
										}
									}
									if (!repeated) {
										enqueue = true;
										serversHELLO.put(ip,
												System.currentTimeMillis());
									}
								}
							}
							break;
						case REQUEST:
							// Se limpia la lista de servidores de los que se ha
							// recibido un mensaje HELLO.
							if (!serversHELLO.isEmpty()) {
								serversHELLO.clear();
							}

							if (message.getType() == MessageType.READY) {
								enqueue = true;
							} else if (message.getType() == MessageType.REJECT) {
								enqueue = true;
							}
							break;
						case PLAY:
							if (message.getType() == MessageType.DATA) {
								enqueue = true;
							}
							break;
						}

						// Se encola el mensaje si fue marcado.
						if (enqueue) {
							messageQueue.insertMessage(message);
						}
					}
				} catch (IOException e) {
					Log.d(TAG, "(C) Error receiving message: " + e.getMessage());
					e.printStackTrace();
				}
			}
			// Se vacia la cola de mensajes.
			messageQueue.clearQueue();
		}

	} // Fin clase interna 'ClientReceiveThread'

	/**
	 * Implementa el diagrama de estados que define el comportamiento del
	 * cliente. LISTEN <-> REQUEST > PLAY > END > LISTEN.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 18-06-2014
	 */
	private class ClientDiagramThread extends Thread {

		/*-----------*/
		/* ATRIBUTOS */
		/*-----------*/
		private Thread thisThread = null; // Referencia al Thread.
		private SOASMessage rMessage = null; // Mensaje recibido.
		private SOASMessage sMessage = null; // Mensaje a enviar.
		private String serverIP = ""; // IP servidor.
		private String rtspPort = ""; // Puerto RTSP.
		private int[] valParams = { 20, 5, 90, 0 }; // Parametros validacion.
		private SQLiteDatabase db = null; // Acceso a BD.

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Implementa el diagrama de estados del cliente.
		 */
		@Override
		public void run() {
			// Variable que guarda el estado actual accediendo de manera
			// sincronizada a la variable compartida que guarda el estado del
			// cliente.
			ClientState tmp_state;

			// Se guarda la referencia al thread para detectar cuando es
			// interrumpido.
			thisThread = Thread.currentThread();

			// Se recuperan los valores de los parametros de validacion.
			SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
					Context.MODE_PRIVATE);
			valParams[0] = prefs.getInt("direction", valParams[0]);
			valParams[1] = prefs.getInt("location", valParams[1]);
			valParams[2] = prefs.getInt("overtaking", valParams[2]);
			valParams[3] = prefs.getInt("disable_val", valParams[3]);

			// Se vacia el log de la sesion.
			db = getApplicationContext().openOrCreateDatabase("sessionLogs",
					MODE_PRIVATE, null);
			db.execSQL("DROP TABLE IF EXISTS clientLog");
			db.close();

			// Mientras el sistema no sea detenido el cliente ejecuta el
			// diagrama de estados que define su comportamiento.
			addInfoToLog("Session starts");
			while ((!thisThread.isInterrupted()) && (thisThread == dThread)) {
				synchronized (state) {
					tmp_state = state;
				}
				switch (tmp_state) {
				case LISTEN:
					doListen();
					break;
				case REQUEST:
					doRequest();
					break;
				case PLAY:
					doPlay();
					break;
				case END:
					doEnd();
					break;
				}
			}
			addInfoToLog("Session ends");
		}

		/**
		 * Permite detener la ejecucion del hilo que implementa el diagrama de
		 * estados.
		 */
		public void stopThread() {
			dThread.interrupt();
			dThread = null;
		}

		/**
		 * Implementa el comportamiento del Cliente en el estado LISTEN.
		 */
		private void doListen() {
			// Variables locales.
			HashMap<String, double[]> candidates = new HashMap<String, double[]>();
			long timeout = 0;
			long timeDiff = 0;
			boolean serverFound = false;

			// Se busca un servidor optimo al que solicitar el inicio de una
			// sesion de streaming RTSP.
			do {
				// Se espera la llegada de un mensaje HELLO.
				Log.d(TAG, "(C) Waiting <HELLO>");
				timeDiff = System.currentTimeMillis();
				rMessage = messageQueue.takeMessage(timeout);
				timeDiff = System.currentTimeMillis() - timeDiff;

				if (rMessage == null) { // TIMEOUT.
					if (!candidates.isEmpty()) {
						// Se guarda la IP del servidor mas optimo.
						serverIP = bestValidServer(candidates);
						serverFound = true;
						Log.d(TAG, "(C) Selected the best server: " + serverIP);

						// Se limpia la cola de mensajes.
						messageQueue.clearQueue();

						// Se cambia de estado.
						synchronized (state) {
							state = ClientState.REQUEST;
						}
					}
				} else if (rMessage.getType() == MessageType.HELLO) { // HELLO.
					Log.d(TAG, "(C) <HELLO> received from " + rMessage.getIp());
					addInfoToLog("<HELLO> received from " + rMessage.getIp());

					// Se valida el mensaje.
					boolean serverOK = isValidServer(rMessage.getLocation());
					if (serverOK) { // Servidor VALIDO.
						Log.d(TAG, "(C) Valid server: " + rMessage.getIp());
						addInfoToLog("Valid server: " + rMessage.getIp());

						// Se añade a la lista de candidatos.
						candidates
								.put(rMessage.getIp(), rMessage.getLocation());

						// Se actualiza el timeout para esperar a otros
						// candidatos.
						if (timeout == 0) {
							timeout = 2000; // 2 sg.
						} else {
							timeout = 2000 - timeDiff;
						}
					} else { // Servidor INVALIDO.
						Log.d(TAG, "(C) Invalid server: " + rMessage.getIp());
						addInfoToLog("Invalid server: " + rMessage.getIp());
					}
				}
			} while ((!serverFound) && (!thisThread.isInterrupted())
					&& (thisThread == dThread));
		}

		/**
		 * Implementa el comportamiento del Cliente en el estado REQUEST.
		 */
		private void doRequest() {
			// Variables locales.
			int attempts = 1;

			// Se solicita la conexion al servidor. Se realizaran un maximo de 3
			// intentos.
			do {
				// Se envia la solicitud de conexion al servidor.
				sMessage = new SOASMessage();
				sMessage.setType(SOASMessage.MessageType.REQUEST);
				sMessage.setIp(AppContext.getIPAddress(true));
				sMessage.setLocation(getLocation());
				sMessage.setMaxResolution(getMaxResolution());
				sendMessage(serverIP, AppContext.RECEIVE_SERVER_PORT, sMessage);
				Log.d(TAG, "(C) <REQUEST> sent to " + serverIP);
				addInfoToLog("<REQUEST> sent to " + serverIP);

				// Se espera la respuesta del servidor durante 3 sg.
				rMessage = messageQueue.takeMessage(3000);
				if (rMessage == null) { // TIMEOUT.
					Log.d(TAG, "(C) TIMEOUT waiting <READY> or <REJECT> from "
							+ serverIP);
					addInfoToLog("TIMEOUT waiting <READY> or <REJECT>");

					attempts++;
					if (attempts > 3) {
						// El servidor no respondio a tiempo.
						// Se cambia de estado.
						synchronized (state) {
							state = ClientState.LISTEN;
						}
					}
				} else if (rMessage.getIp().equalsIgnoreCase(serverIP)) {
					if (rMessage.getType() == MessageType.READY) { // READY.
						Log.d(TAG, "(C) <READY> received from " + serverIP);
						addInfoToLog("<READY> received from " + serverIP);

						// Se limpia la cola de mensajes.
						messageQueue.clearQueue();

						// Se guarda el puerto RTSP.
						rtspPort = String.valueOf(rMessage.getRTSPPort());

						// Se cambia de estado.
						synchronized (state) {
							state = ClientState.PLAY;
						}
						attempts = 4;
					} else if (rMessage.getType() == MessageType.REJECT) { // REJECT.
						Log.d(TAG, "(C) <REJECT> received from " + serverIP);
						addInfoToLog("<REJECT> received from " + serverIP);

						// Se limpia la cola de mensajes.
						messageQueue.clearQueue();

						// Se cambia de estado.
						synchronized (state) {
							state = ClientState.LISTEN;
						}
						attempts = 4;
					}
				} else { // El mensaje no es del servidor. Posible ataque.
					attempts++;
					Log.d(TAG, "(C) Message from an unknown server: "
							+ rMessage.getIp());
				}
			} while ((attempts <= 3) && (!thisThread.isInterrupted())
					&& (thisThread == dThread));
		}

		/**
		 * Implementa el comportamiento del Cliente en el estado PLAY.
		 */
		private void doPlay() {
			// Variables locales.
			int attempts = 1;

			// Se anuncia al hilo principal del servicio que arranque la
			// reproduccion del streaming.
			Message msg = Message.obtain(handler, StartStreaming);
			Bundle bundle = new Bundle();
			bundle.putString("ip", serverIP);
			bundle.putString("port", rtspPort);
			msg.setData(bundle);
			msg.sendToTarget();

			// Se actualiza el flag de reproduccion de streaming RTSP.
			synchronized (isPlaying) {
				isPlaying = true;
			}

			// Se inicia la recepcion (DATA) y envio (DATA_ACK) de mensajes de
			// control de conexion. Se permite un maximo de 3 reenvios DATA_ACK
			// ante la falta de un mensaje DATA.
			addInfoToLog("Receiving video stream from " + serverIP);
			do {
				// Se espera la llegada de un mensaje DATA emitido por el
				// servidor durante 3 sg.
				rMessage = messageQueue.takeMessage(3000);
				if (rMessage == null) { // TIMEOUT.
					Log.d(TAG, "(C) TIMEOUT waiting <DATA> from " + serverIP);

					attempts++;
					if (attempts > 3) { // Se alcanzo el maximo de intentos.
						// Se cambia de estado.
						synchronized (state) {
							state = ClientState.END;
						}
					} else {
						// Se intenta recuperar la conexion reeviando el mensaje
						// DATA_ACK.
						sMessage = new SOASMessage();
						sMessage.setIp(AppContext.getIPAddress(true));
						sMessage.setType(SOASMessage.MessageType.DATA_ACK);
						sendMessage(serverIP, AppContext.RECEIVE_SERVER_PORT,
								sMessage);
						Log.d(TAG, "(C) <DATA_ACK> forwarded to " + serverIP);
					}
				} else if ((rMessage.getIp().equalsIgnoreCase(serverIP))
						&& (rMessage.getType() == MessageType.DATA)) {
					Log.d(TAG, "(C) <DATA> received from " + serverIP);

					// Se actualiza la velocidad en el video.
					updateServerSpeed(rMessage.getSpeed());

					// Se reinicia el numero de intentos.
					attempts = 1;

					// Se responde al servidor con un DATA_ACK o con un END, en
					// funcion de si la visualizacion del video continua siendo
					// util o no.
					sMessage = new SOASMessage();
					sMessage.setIp(AppContext.getIPAddress(true));
					if (isStreamingUseful(rMessage.getLocation())) { // DATA_ACK.
						// Se envia un mensaje DATA_ACK.
						sMessage.setType(SOASMessage.MessageType.DATA_ACK);
						Log.d(TAG, "(C) <DATA_ACK> sent to " + serverIP);
					} else { // END.
						// Se envia un mensaje END.
						sMessage.setType(SOASMessage.MessageType.END);
						Log.d(TAG, "(C) <END> sent to " + serverIP);

						// Se cambia de estado.
						synchronized (state) {
							state = ClientState.END;
						}
						attempts = 4;
					}
					sendMessage(serverIP, AppContext.RECEIVE_SERVER_PORT,
							sMessage);
				} else { // El mensaje no es del servidor. Posible ataque.
					attempts++;
					Log.d(TAG, "(C) Message from an unknown server: "
							+ rMessage.getIp());
				}
			} while ((attempts <= 3) && (!thisThread.isInterrupted())
					&& (thisThread == dThread));
		}

		/**
		 * Implementa el comportamiento del Cliente en el estado END.
		 */
		private void doEnd() {
			Log.d(TAG, "(C) Streaming session ended with: " + serverIP);
			addInfoToLog("Streaming session ended with: " + serverIP);

			// Se anuncia al hilo principal que detenga la reproduccion del
			// streaming y se limpia la cola de mensajes.
			Message msg = Message.obtain(handler, StopStreaming);
			msg.sendToTarget();
			messageQueue.clearQueue();

			// Se actualiza el flag de reproduccion de streaming RTSP.
			synchronized (isPlaying) {
				isPlaying = false;
			}

			// Se limpian las variables globales del hilo.
			rMessage = null;
			sMessage = null;
			serverIP = "";

			// Se vuelve al estado inicial.
			synchronized (state) {
				state = ClientState.LISTEN;
			}
			Log.d(TAG, "(C) Return to the initial state");
		}

		/**
		 * Indica si un servidor es valido o no para proporcionar el servicio de
		 * streaming, evaluando su localizacion respecto a la del dispositivo
		 * cliente.
		 * 
		 * @param serverLocation
		 *            Localizacion servidor (Vector 2D)
		 * @return Servidor valido-True / Servidor no valido-False
		 */
		private boolean isValidServer(double[] serverLoc) {
			// Se comprueba si la validacion esta desactivada.
			if (valParams[3] == 1) {
				// Se acepta por defecto.
				return true;
			}
			// Para que sea un servidor valido debe ir en la misma direccion que
			// el cliente y delante de el.
			double[] serverL = serverLoc;
			double[] clientL = getLocation();
			if ((lastLocation[0] != null) && (lastLocation[1] != null)) { // LOCALIZACION-DISPONIBLE.
				// Se valida que ambos vectores cuenten con 2 puntos distintos,
				// es decir que no tengan modulo nulo.
				if (AppContext.isOnePointVector(serverL)
						|| AppContext.isOnePointVector(clientL)) {
					// No se puede continuar con la validacion.
					Log.d(TAG_2, "(C) One point vector");
					addInfoToLog("(VAL) One point vector");
					return false;
				} else {
					// Validacion misma direccion-sentido. Se evalua el angulo
					// que forman los dos vectores.
					double degrees = AppContext.getDegrees(serverL, clientL);
					if (degrees <= valParams[0]) { // Misma direccion-sentido.
						// Validacion servidor delante de cliente. Se compara el
						// angulo formado por el vector de direccion del cliente
						// y el vector que une la posicion actual del cliente
						// con la del servidor.
						double[] clientToServer = { clientL[2], clientL[3],
								serverL[2], serverL[3] };
						degrees = AppContext
								.getDegrees(clientL, clientToServer);
						if (degrees <= valParams[1]) { // Delante.
							return true;
						} else { // No delante.
							Log.d(TAG_2,
									"(C) Server isn't in front of the client - "
											+ Double.toString(degrees)
											+ " degrees");
							addInfoToLog("(VAL) Server isn't in front of the client");
							return false;
						}
					} else { // Diferente direccion-sentido.
						Log.d(TAG_2,
								"(C) Different directions - "
										+ Double.toString(degrees) + " degrees");
						addInfoToLog("(VAL) Different directions");
						return false;
					}
				}
			} else { // LOCALIZACION-NO-DISPONIBLE.
				// No se puede efectuar la validacion.
				Log.d(TAG_2, "(C) Location not available");
				addInfoToLog("(VAL) Location not available");
				return false;
			}
		}

		/**
		 * Devuelve el servidor de streaming mas optimo de entre un conjunto de
		 * servidores validos.
		 */
		private String bestValidServer(HashMap<String, double[]> candidates) {
			// Variables locales
			String nearbyCandidate = "";
			float minDistance = Float.MAX_VALUE;

			// Se selecciona el candidato mas cercano.
			Iterator<String> iterator = candidates.keySet().iterator();
			while (iterator.hasNext()) {
				String cadidateIP = iterator.next();
				double[] candidateL = candidates.get(cadidateIP);
				double[] clientL = getLocation();
				float[] distance = { 9999 };
				Location.distanceBetween(clientL[2], clientL[3], candidateL[2],
						candidateL[3], distance);
				if (distance[0] < minDistance) { // Actualizar candidato.
					minDistance = distance[0];
					nearbyCandidate = cadidateIP;
				}
			}

			Log.d(TAG_2, "(C) Best candidate: " + nearbyCandidate);
			return nearbyCandidate;
		}

		/**
		 * Devuelve la resolucion maxima soportada por el dispositivo.
		 * 
		 * @return Posicion y direccion (Vector 2D)
		 */
		private int[] getMaxResolution() {
			// Variables locales.
			String[] resolutions = { "1920x1080", "1280x720", "720x480",
					"480x320", "320x240" };
			int[] maxResolution = { 320, 240 };

			// Se obtienen las dimensiones de la pantalla.
			int[] dimens = AppContext
					.getScreenDimensions(getApplicationContext());
			int width = dimens[0];
			int height = dimens[1];

			// Se busca la resolucion optima en la que mejor encajan las
			// dimensiones del telefono.
			for (int i = 0; i <= resolutions.length; i++) {
				int resWidth = Integer.parseInt(resolutions[i].split("x")[0]);
				int resHeight = Integer.parseInt(resolutions[i].split("x")[1]);
				if ((resWidth <= width) && (resHeight <= height)) {
					maxResolution[0] = resWidth;
					maxResolution[1] = resHeight;
					break;
				}
			}
			Log.d(TAG, "Max resolution: " + maxResolution[0] + "x"
					+ maxResolution[1]);

			return maxResolution;
		}

		/**
		 * Permite saber si el streming RTSP es util o no, para lo cual se
		 * evalua la localizacion del cliente y el servidor.
		 * 
		 * @param serverLocation
		 *            Localizacion del servidor
		 * @return Continuar-True / Finalizar-False
		 */
		private boolean isStreamingUseful(double[] serverLoc) {
			// Se obtiene el estado del flag de reproduccion de streaming.
			Boolean tmp_isPlaying;
			synchronized (isPlaying) {
				tmp_isPlaying = isPlaying;
			}

			if (tmp_isPlaying) { // Reproductor RTSP ON.
				// Se comprueba si la validacion esta desactivada.
				if (valParams[3] == 1) {
					// No se detiene la sesion.
					return true;
				}
				// Se evalua si el cliente a sobrepasado al servidor. Se valida
				// el angulo que forman el vector de direccion del cliente con
				// el vector que une la posicion actual del cliente con la
				// posicion actual del servidor.
				double[] clientL = getLocation();
				double[] clientToServer = { clientL[2], clientL[3],
						serverLoc[2], serverLoc[3] };
				double degrees = AppContext.getDegrees(clientL, clientToServer);
				if (degrees <= valParams[2]) { // Detras.
					return true;
				} else { // Delante.
					Log.d(TAG_2, "(C) Client overtook the server");
					return false;
				}
			} else { // Reproductor RTSP OFF.
				return false;
			}
		}

		/**
		 * Permite mostrar la velocidad a la que se deplaza el dispositivo
		 * servidor al mismo tiempo que se reproduce el streaming RTSP.
		 * 
		 * @param speed
		 *            Velocidad servidor.
		 */
		private void updateServerSpeed(float speed) {
			// Se envia la velocidad al reproductor para que la actualice.
			Intent intent = new Intent("player_Receiver");
			intent.putExtra("speed", String.valueOf((int) speed));
			sendBroadcast(intent);
		}

		/**
		 * Permite enviar un mensaje a un dispositivo servidor indicando su
		 * direccion IP y el puerto de escucha.
		 * 
		 * @param ip
		 *            Direccion IP destino
		 * @param port
		 *            Puerto destino
		 * @param message
		 *            Mensaje
		 */
		private void sendMessage(String ip, int port, SOASMessage message) {
			try {
				// Se empaqueta el mensaje en un datagrama UDP.
				byte[] sendBuf = AppContext.serializeMessage(message);
				InetAddress IP;
				IP = InetAddress.getByName(ip);
				DatagramPacket packet = new DatagramPacket(sendBuf,
						sendBuf.length, IP, port);

				// Se pasa el datagrama al hilo de emision de mensajes y se
				// arranca el hilo para que lo envie.
				sThread.setPacket(packet);
				sThread.run();
			} catch (UnknownHostException e) {
				Log.d(TAG, "Error packaging message: " + e.getMessage());
				e.printStackTrace();
			}
		}

		/**
		 * Añade la informacion indicada al log de eventos de la sesion. Para
		 * ello almacena la informacion en una BD SQLite.
		 */
		private void addInfoToLog(String info) {
			// Se almacena la informacion en la base de datos.
			db = getApplicationContext().openOrCreateDatabase("sessionLogs",
					MODE_PRIVATE, null);
			db.execSQL("CREATE TABLE IF NOT EXISTS clientLog (id INTEGER PRIMARY KEY autoincrement, time TEXT NOT NULL, info TEXT NOT NULL)");
			db.execSQL("INSERT INTO clientLog VALUES(null,'[ "
					+ AppContext.getSystemTime() + " ]','" + info + "')");
			db.close();
		}

	} // Fin clase interna 'ClientDiagramThread'

} // Fin clase 'SOASClient'
