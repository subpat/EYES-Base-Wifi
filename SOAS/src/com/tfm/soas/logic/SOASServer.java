package com.tfm.soas.logic;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.rtsp.RtspServer;
import net.majorkernelpanic.streaming.video.VideoQuality;

import com.tfm.soas.R;
import com.tfm.soas.context.AppContext;
import com.tfm.soas.logic.LocationService.LocalBinder;
import com.tfm.soas.logic.SOASMessage.MessageType;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.PixelFormat;
import android.location.Location;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

/**
 * Servicio que implementa el comportamiento SERVIDOR del sistema. Se encargara
 * de:
 * 
 * 1- Anunciar a los dispositivos cliente de otros vehiculos cercanos el
 * servicio de streaming RTSP que ofrece.
 * 
 * 2- Evaluar la ubicacion de el/los interesado/s y responder acordemente a cada
 * uno.
 * 
 * 3- Servir el video RTSP a aquel dispositivo cliente situado justo detras del
 * dispositivo servidor.
 * 
 * 4- Cerrar correctamente la sesion de streaming una vez que no sea necesaria.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 27-05-2014
 */
public class SOASServer extends Service implements SurfaceHolder.Callback {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	private static enum ServerState {
		INI, NOTIFY, REPLY, STREAM, END
	}

	private static final int FPS = 30;
	private static final int BPS = 2000000;
	private static final int ChangeVideoQuality = 1;
	private static final String TAG = "SOASServer";
	private static final String TAG_2 = "Server Validation";

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private ServerState state = ServerState.INI; // Estado Servidor.
	private volatile ServerDiagramThread dThread = null; // Hilo diagrama de estados.
	private volatile ServerAdvertiseThread aThread = null; // Hilo emisor de anuncios.
	private volatile ServerSendThread sThread = null; // Hilo emisor de mensajes.
	private volatile ServerReceiveThread rThread = null; // Hilo receptor de mensajes.
	private SOASMessageQueue messageQueue = null; // Cola de mensajes.
	private LocationService lService = null; // Acceso localizacion.
	private LocationServiceConn lServiceConnection = null; // Gestor conexion.
	private boolean boundLService = false; // Conectado a servicio localizacion.
	private volatile Location[] lastLocation = { null, null }; // Vector 2D.
	private volatile int rtspPort = -1; // Puerto de escucha servidor RTSP.
	private WindowManager mWindowManager = null; // Gestor de Ventanas.
	private SurfaceView mSurfaceView = null; // Superficie de visualizacion 1x1.
	private Handler handler = null; // Comunicacion con el hilo principal.
	private WakeLock wl = null; // WaveLock CPU ON.
	private int soundState = -1; // Estado sonido dispositivo.

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
		// garantizar que el servidor no se detiene.
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
				"Server WaveLock");
		wl.acquire();

		// Se crea el gestor de mensajes que permite la comunicacion entre los
		// hilos secundarios y el hilo principal del servicio.
		handler = new ServerHandler();

		// Se establece conexion con LocationService, a traves del cual
		// solicitar la localizacion del dispositivo.
		Intent intent = new Intent(this, LocationService.class);
		lServiceConnection = new LocationServiceConn();
		bindService(intent, lServiceConnection, Context.BIND_AUTO_CREATE);

		// Se arrancan los hilos que definen el comportamiento del servidor.
		launchThreads();

		// Se arranca el servidor RTSP.
		rtspPort = getAvailablePort();
		int[] defaultRes = { 480, 320 };
		startRTSPServer(rtspPort, defaultRes);

		// Se crea la notificacion que permite detener el sistema desde la barra
		// de notificaciones.
		putNotification();
	}

	/**
	 * Detencion del servicio.
	 */
	@Override
	public void onDestroy() {
		// Se detiene el servidor RTSP.
		stopRTSPServer();

		// Se detienen los hilos que definen el comportamiento del servidor.
		stopThreads();

		// Se cierra la conexion con el servicio de localizacion.
		if (boundLService) {
			unbindService(lServiceConnection);
			boundLService = false;
		}

		// Se libera el WaveLock.
		wl.release();

		// Se restablece el sonido del dispositivo.
		if (soundState != -1) { // Estado modificado.
			AppContext.changeSoundState(getApplicationContext(), soundState);
		}
	}

	/**
	 * Lanza a ejecucion el hilo emisor de anuncios, el hilo receptor de
	 * mensajes, el hilo emisor de mensajes y el hilo que implementa el diagrama
	 * de estados.
	 */
	private void launchThreads() {
		// Se actualiza el estado antes de arrancar los hilos.
		state = ServerState.NOTIFY;

		// Se arranca el hilo receptor de mensajes, y se crea la cola de
		// mensajes donde los almacenara.
		messageQueue = new SOASMessageQueue();
		rThread = new ServerReceiveThread();
		rThread.start();

		// Se arranca el hilo que difunde los anuncios.
		aThread = new ServerAdvertiseThread();
		aThread.start();

		// Se arranca el hilo emisor de mensajes.
		sThread = new ServerSendThread();
		sThread.start();

		// Se arranca el hilo que implementa el diagrama de estados.
		dThread = new ServerDiagramThread();
		dThread.start();
	}

	/**
	 * Interrumpe el hilo emisor de anuncios, el hilo receptor de mensajes, el
	 * hilo emisor de mensajes y el hilo que implementa el diagrama de estados.
	 */
	private void stopThreads() {
		// Se interrumpe el hilo receptor de mensajes.
		rThread.stopThread();

		// Se interrumpe el hilo que difunde los anuncios.
		aThread.stopThread();

		// Se interrumpe el hilo emisor de mensajes.
		sThread.stopThread();

		// Se interrumpe el hilo que implementa el diagrama de estados.
		dThread.stopThread();
	}

	/**
	 * Devuelve un puerto que no se encuentre actualmente en uso de entre el
	 * rango de puertos Registrados (1024-49151).
	 * 
	 * @return Puerto disponible.
	 */
	private int getAvailablePort() {
		while (true) {
			// Se escoge un Puerto Registrado al azar.
			int random = (int) (Math.random() * 49151 + 1024);

			// Se intenta abrir un Socket UDP en este puerto.
			try {
				DatagramSocket ds = new DatagramSocket(random);
				// El puerto se encuentra disponible.
				ds.close();
				return random;
			} catch (SocketException e) {
				// El puerto se encuentra ocupado.
				continue;
			}
		}
	}

	/**
	 * Crea una notificacion que permite parar el sistema desde la barra de
	 * notificaciones.
	 */
	@SuppressWarnings("deprecation")
	private void putNotification() {
		// Intent que apunta al servicio que detiene el sistema.
		Context appContext = getApplicationContext();
		Intent notificationIntent = new Intent(appContext,
				StopSOASService.class);
		PendingIntent pendingIntent = PendingIntent.getService(appContext, 0,
				notificationIntent, 0);

		// Se crea y muestra la notificacion.
		if (android.os.Build.VERSION.SDK_INT >= 11) { // API >= 11
			Notification notification = new Notification.Builder(appContext)
					.setContentTitle("SOAS").setTicker("SOAS ON")
					.setContentText("Tap to stop SOAS")
					.setSmallIcon(R.drawable.ic_launcher)
					.setContentIntent(pendingIntent).build();
			startForeground(1234, notification);

		} else { // API <= 10
			Notification notification = new Notification(
					R.drawable.ic_launcher, "SOAS ON",
					System.currentTimeMillis());
			notification.setLatestEventInfo(appContext, "SOAS",
					"Tap to stop SOAS", pendingIntent);
			startForeground(1234, notification);
		}
	}

	/**
	 * Crea una superficie de visualizacion de 1x1 px que permite capturar video
	 * en segundo plano.
	 */
	@SuppressLint("RtlHardcoded")
	@SuppressWarnings("deprecation")
	private void putSurface() {
		// Caracteristicas de la SurfaceView
		LayoutParams layoutParams = new WindowManager.LayoutParams(1, 1,
				WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
				WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
				PixelFormat.TRANSLUCENT);
		layoutParams.gravity = Gravity.LEFT | Gravity.TOP;

		// Se crea la superficie de visualizacion y se establece como oyente de
		// eventos el propio servicio.
		mSurfaceView = new SurfaceView(getApplicationContext(), null);
		mSurfaceView.getHolder().addCallback(this);
		mSurfaceView.getHolder().setType(
				SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		// Se añade la superficie al gestor de ventanas.
		mWindowManager = (WindowManager) this
				.getSystemService(Context.WINDOW_SERVICE);
		mWindowManager.addView(mSurfaceView, layoutParams);
	}

	/**
	 * Permite arrancar el servidor RTSP en un puerto determinado y con una
	 * resolucion de video concreta.
	 * 
	 * @param port
	 *            Puerto en el que escucha el servidor
	 * @param resolution
	 *            Resolucion de video
	 * 
	 */
	private void startRTSPServer(int port, int[] resolution) {
		// Se establece el puerto del servidor RTSP.
		Editor editor = PreferenceManager.getDefaultSharedPreferences(this)
				.edit();
		editor.putString(RtspServer.KEY_PORT, String.valueOf(port));
		editor.commit();

		// MediaRecorder requiere de una SurfaceView para poder capturar video.
		// Para que el usuario no sea consciente de la grabacion, se crea una
		// SurfaceView transparente de tamaño 1x1 en la esquina superior
		// izquierda.
		putSurface();

		// Se establecen las caracteristicas de la sesion de Streaming para que
		// cuando la Surface se haya creado el servidor pueda arrancar
		// correctamente.
		SessionBuilder
				.getInstance()
				.setContext(getApplicationContext())
				.setAudioEncoder(SessionBuilder.AUDIO_NONE)
				.setVideoEncoder(SessionBuilder.VIDEO_H264)
				.setSurfaceHolder(mSurfaceView.getHolder())
				.setVideoQuality(
						new VideoQuality(resolution[0], resolution[1], FPS, BPS));
		Log.d(TAG, "(S) RTSP Server started in port " + port + ": "
				+ resolution[0] + "x" + resolution[1] + " px - " + FPS
				+ " fps - " + (BPS / 1000) + " kbps");
	}

	/**
	 * Permite detener el servidor RTSP.
	 */
	private void stopRTSPServer() {
		// Se detiene el servidor RTSP.
		stopService(new Intent(this, RtspServer.class));

		// Se elimina la Surface de 1x1.
		mWindowManager.removeView(mSurfaceView);
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
	 * Devuelve la velocidad actual del dispositivo.
	 * 
	 * @return Velocidad en Km/h
	 */
	private synchronized float getSpeed() {
		// Variable locales.
		float speed = 0;

		// Se actualiza la localizacion actual.
		getLocation();

		// Se obtiene y devuelve la velocidad en Km/h.
		if ((lastLocation[0] != null) && (lastLocation[1] != null)) {
			speed = lastLocation[1].getSpeed();
			if (speed != 0.0) { // AUTOMATICA.
				speed = (((speed) / 1000) * 3600);
			} else { // MANUAL.
				float distance = lastLocation[0].distanceTo(lastLocation[1]);
				long time = ((lastLocation[1].getTime() - lastLocation[0]
						.getTime()) / 1000);
				if (time > 0) {
					speed = (((distance / time) / 1000) * 3600);
				} else {
					speed = 0;
				}
			}
		} else {
			speed = 0;
		}

		return speed;
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

	/*---------*
	 | SURFACE |
	 *---------*/
	/**
	 * Se ejecuta inmediatamente despues de que la SurfaceView se haya creado
	 * correctamente.
	 * 
	 * @param holder
	 *            El SurfaceHolder que contiene a la Surface que se ha creado.
	 * 
	 */
	@Override
	public void surfaceCreated(SurfaceHolder surfaceHolder) {
		// Se arranca el servidor RTSP.
		startService(new Intent(this, RtspServer.class));
	}

	/**
	 * Se ejecuta inmediatamente despues de que la SurfaceView haya sufrido
	 * alguna modificacion.
	 * 
	 * @param holder
	 *            El SurfaceHolder que contiene a la Surface que ha cambiado.
	 * @param format
	 *            El nuevo PixelFormat de la Surface.
	 * @param w
	 *            El nuevo ancho de la Surface
	 * @param h
	 *            El nuevo alto de la Surface
	 * 
	 */
	@Override
	public void surfaceChanged(SurfaceHolder surfaceHolder, int format,
			int width, int height) {
	}

	/**
	 * Se ejecuta inmediatamente antes de que la SurfaceView sea destruida.
	 * 
	 * @param holder
	 *            El SurfaceHolder que contiene a la Surface que se va a
	 *            destruir.
	 * 
	 */
	@Override
	public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
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
	 * Handler encargado de recibir, evaluar y gestionar adecuadamente los
	 * mensajes emitidos desde los hilos secundarios hacia el hilo principal del
	 * servicio.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 06-07-2014
	 */
	private class ServerHandler extends Handler {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Este metodo permite gestionar los distintos eventos que recibe el
		 * handler.
		 * 
		 * @param msg
		 *            Mensaje con info del evento ocurrido
		 */
		@Override
		public void handleMessage(Message msg) {
			// Se desactiva el sonido del sistema para que cuando la camara
			// empiece a grabar no se emita ningun sonido.
			if (soundState == -1) { // Estado no modificado aun.
				soundState = AppContext.soundState(getApplicationContext());
			}
			AppContext.changeSoundState(getApplicationContext(),
					AudioManager.RINGER_MODE_SILENT);

			// Se evalua el tipo de mensaje.
			if (msg.what == ChangeVideoQuality) { // Cambiar calidad video RTSP.
				Bundle bundle = msg.getData();
				int resolution[] = bundle.getIntArray("maxRes");

				SessionBuilder.getInstance()
						.setVideoQuality(
								new VideoQuality(resolution[0], resolution[1],
										FPS, BPS));
				Log.d(TAG, "(S) RTSP Video quality changed: " + resolution[0]
						+ "x" + resolution[1] + " px - " + FPS + " fps - "
						+ (BPS / 1000) + " kbps");

				return;
			}
		}

	} // Fin clase interna 'ServerHandler'

	/**
	 * Hilo emisor de notificaciones, encargado de anunciar a los dispositivos
	 * cliente el servicio de streaming RTSP que ofrece.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 02-06-2014
	 */
	private class ServerAdvertiseThread extends Thread {

		/*-----------*/
		/* ATRIBUTOS */
		/*-----------*/
		private DatagramSocket advertiseSocket = null; // Socket para anuncios.

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Constructor para instancias de la clase AdvertiseThread.
		 */
		public ServerAdvertiseThread() {
			try {
				// Se arranca el socket UDP de envio de anuncios.
				advertiseSocket = new DatagramSocket();
				advertiseSocket.setBroadcast(true);
			} catch (SocketException e) {
				Log.d(TAG,
						"(S) Error opening Advertise Socket: " + e.getMessage());
				e.printStackTrace();

				// Se interrumpe el hilo.
				Thread.currentThread().interrupt();

				// Se detiene el sistema.
				startService(new Intent(SOASServer.this, StopSOASService.class));
			}
		}

		/**
		 * Permite detener la ejecucion del hilo que anuncia el servicio RTSP.
		 */
		public void stopThread() {
			aThread.interrupt();
			advertiseSocket.close();
			aThread = null;
		}

		/**
		 * Implementa el comportamiento del hilo que anuncia el servicio RTSP.
		 */
		@Override
		public void run() {
			// Mientras no se interrupa el hilo se envian anuncios.
			Thread thisThread = Thread.currentThread();
			while ((!thisThread.isInterrupted()) && (thisThread == aThread)) {
				try {
					// Se crea el mensaje y se establece su contenido.
					SOASMessage message = new SOASMessage();
					message.setType(SOASMessage.MessageType.HELLO);
					message.setLocation(getLocation());
					synchronized (state) { // IP servidor.
						if (state == ServerState.STREAM) {
							// Indica servidor ocupado.
							message.setIp("X.X.X.X");
						} else {
							message.setIp(AppContext.getIPAddress(true));
						}
					}

					// Se empaqueta el mensaje en un datagrama UDP broadcast.
					byte[] sendBuf = AppContext.serializeMessage(message);
					DatagramPacket packet = new DatagramPacket(sendBuf,
							sendBuf.length,
							InetAddress.getByName("255.255.255.255"),
							AppContext.RECEIVE_CLIENT_PORT);

					// Se envia el mensaje.
					advertiseSocket.send(packet);

					// Se espera 1 segundo hasta el envio del siguiente anuncio.
					Thread.sleep(1000);

				} catch (InterruptedException e1) {
					Log.d(TAG, "(S) AThread Interrupted: " + e1.getMessage());
					e1.printStackTrace();
					break;
				} catch (IOException e2) {
					Log.d(TAG, "(S) Error sending advert: " + e2.getMessage());
					e2.printStackTrace();
				}
			}
		}

	} // Fin clase interna 'ServerAdvertiseThread'

	/**
	 * Hilo emisor de mensajes.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 08-06-2014
	 */
	private class ServerSendThread extends Thread {

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
		public ServerSendThread() {
			try {
				// Se arranca el socket UDP de envio de mensajes.
				sendSocket = new DatagramSocket();
			} catch (SocketException e) {
				Log.d(TAG, "(S) Error opening Send Socket: " + e.getMessage());
				e.printStackTrace();

				// Se interrumpe el hilo.
				Thread.currentThread().interrupt();

				// Se detiene el sistema.
				startService(new Intent(SOASServer.this, StopSOASService.class));
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
					Log.d(TAG, "(S) Error sending message: " + e.getMessage());
					e.printStackTrace();
				}
			}
		}

	} // Fin clase interna 'ServerSendThread'

	/**
	 * Hilo receptor de mensajes, encargado de escuchar, filtrar y encolar los
	 * mensajes enviados por los dispositivos cliente.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 02-06-2014
	 */
	private class ServerReceiveThread extends Thread {

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
		public ServerReceiveThread() {
			try {
				// Se arranca el socket UDP de recepcion de mensajes.
				receiveSocket = new DatagramSocket(
						AppContext.RECEIVE_SERVER_PORT);
			} catch (SocketException e) {
				Log.d(TAG,
						"(S) Error opening Receive Socket: " + e.getMessage());
				e.printStackTrace();

				// Se interrumpe el hilo.
				Thread.currentThread().interrupt();

				// Se detiene el sistema.
				startService(new Intent(SOASServer.this, StopSOASService.class));
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
			// servidor.
			ServerState tmp_state;

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
						case NOTIFY:
							if (message.getType() == MessageType.REQUEST) {
								enqueue = true;
							}
							break;
						case STREAM:
							if (message.getType() == MessageType.DATA_ACK) {
								enqueue = true;
							} else if (message.getType() == MessageType.END) {
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
					Log.d(TAG, "(S) Error receiving message: " + e.getMessage());
					e.printStackTrace();
				}
			}
			// Se vacia la cola de mensajes.
			messageQueue.clearQueue();
		}

	} // Fin clase interna 'ServerSendThread'

	/**
	 * Implementa el diagrama de estados que define el comportamiento del
	 * servidor. NOTIFY <-> REPLY > STREAM > END > NOTIFY.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 18-06-2014
	 */
	private class ServerDiagramThread extends Thread {

		/*-----------*/
		/* ATRIBUTOS */
		/*-----------*/
		private Thread thisThread = null; // Referencia al Thread.
		private SOASMessage rMessage = null; // Mensaje recibido.
		private SOASMessage sMessage = null; // Mensaje a enviar.
		private boolean clientOK = false; // Cliente Valido/Invalido.
		private String clientIP = ""; // IP cliente.
		private int[] valParams = { 20, 5, 0 }; // Parametros validacion.
		private SQLiteDatabase db = null; // Acceso a BD.

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Implementa el diagrama de estados del servidor.
		 */
		@Override
		public void run() {
			// Variable que guarda el estado actual accediendo de manera
			// sincronizada a la variable compartida que guarda el estado del
			// servidor.
			ServerState tmp_state;

			// Se guarda la referencia al thread para detectar cuando es
			// interrumpido.
			thisThread = Thread.currentThread();

			// Se recuperan los valores de los parametros de validacion.
			SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
					Context.MODE_PRIVATE);
			valParams[0] = prefs.getInt("direction", valParams[0]);
			valParams[1] = prefs.getInt("location", valParams[1]);
			valParams[2] = prefs.getInt("disable_val", valParams[2]);

			// Se vacia el log de la sesion.
			db = getApplicationContext().openOrCreateDatabase("sessionLogs",
					MODE_PRIVATE, null);
			db.execSQL("DROP TABLE IF EXISTS serverLog");
			db.close();

			// Mientras el sistema no sea detenido el servidor ejecuta el
			// diagrama de estados que define su comportamiento.
			addInfoToLog("Session starts");
			while ((!thisThread.isInterrupted()) && (thisThread == dThread)) {
				synchronized (state) {
					tmp_state = state;
				}
				switch (tmp_state) {
				case NOTIFY:
					doNotify();
					break;
				case REPLY:
					doReply();
					break;
				case STREAM:
					doStream();
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
		 * Implementa el comportamiento del Servidor en el estado NOTIFY.
		 */
		private void doNotify() {
			// Variables locales.
			boolean requestReceived = false;

			// Se espera la llegada de un mensaje REQUEST.
			do {
				Log.d(TAG, "(S) Waiting <REQUEST>");
				rMessage = messageQueue.takeMessage(0);
				if (rMessage.getType() == MessageType.REQUEST) {
					// Se guarda la IP del cliente.
					clientIP = rMessage.getIp();
					Log.d(TAG, "(S) <REQUEST> received from " + clientIP);
					addInfoToLog("<REQUEST> received from " + clientIP);

					// Se valida el mensaje.
					clientOK = isValidClient(rMessage.getLocation());

					// Se cambia de estado.
					synchronized (state) {
						state = ServerState.REPLY;
					}
					requestReceived = true;
				}
			} while ((!requestReceived) && (!thisThread.isInterrupted())
					&& (thisThread == dThread));
		}

		/**
		 * Implementa el comportamiento del Servidor en el estado REPLY.
		 */
		private void doReply() {
			// Se crea el mensaje de respuesta a la solicitud.
			sMessage = new SOASMessage();
			sMessage.setIp(AppContext.getIPAddress(true));

			if (clientOK) { // VALIDO.
				Log.d(TAG, "(S) Valid client: " + clientIP);
				addInfoToLog("Valid client: " + clientIP);

				// Se rellena el mensaje de aceptacion.
				sMessage.setType(SOASMessage.MessageType.READY);
				sMessage.setLocation(getLocation());
				sMessage.setRTSPPort(rtspPort);
				sMessage.setSpeed(getSpeed());

				// Se limpia la cola de mensajes.
				messageQueue.clearQueue();

				// Se anuncia al hilo principal del servicio que adapte la
				// calidad del video a la del cliente.
				Message msg = Message.obtain(handler, ChangeVideoQuality);
				Bundle bundle = new Bundle();
				bundle.putIntArray("maxRes", rMessage.getMaxResolution());
				msg.setData(bundle);
				msg.sendToTarget();

				// Se cambia de estado.
				synchronized (state) {
					state = ServerState.STREAM;
				}
			} else { // INVALIDO.
				Log.d(TAG, "(S) Invalid client: " + clientIP);
				addInfoToLog("Invalid client: " + clientIP);

				// Se rellena el mensaje de rechazo.
				sMessage.setType(SOASMessage.MessageType.REJECT);

				// Se cambia de estado.
				synchronized (state) {
					state = ServerState.NOTIFY;
				}
			}

			// Se envia la respuesta por triplicado para asegurar que es
			// recibida por el cliente.
			for (int i = 1; i <= 3; i++) {
				sendMessage(clientIP, AppContext.RECEIVE_CLIENT_PORT, sMessage);
			}
			Log.d(TAG, "(S) Reply sent to " + clientIP);
			addInfoToLog("Reply sent to " + clientIP);
		}

		/**
		 * Implementa el comportamiento del Servidor en el estado STREAM.
		 */
		private void doStream() {
			// Variables locales.
			int attempts = 1;

			// Se inicia el envio (DATA) y recepcion (DATA_ACK) de mensajes de
			// control de conexion. Se permite un maximo de 3 reenvios DATA
			// ante la falta de un mensaje DATA_ACK.
			addInfoToLog("Sending video stream to " + clientIP);
			do {
				// Se envia un mensaje DATA.
				sMessage = new SOASMessage();
				sMessage.setType(SOASMessage.MessageType.DATA);
				sMessage.setIp(AppContext.getIPAddress(true));
				sMessage.setLocation(getLocation());
				sMessage.setSpeed(getSpeed());
				sendMessage(clientIP, AppContext.RECEIVE_CLIENT_PORT, sMessage);
				Log.d(TAG, "(S) <DATA> sent to " + clientIP);

				// Se espera a la llegada de un mensaje DATA_ACK o un mensaje
				// END emitido por el cliente, durante 3 sg.
				rMessage = messageQueue.takeMessage(3000);
				if (rMessage == null) { // TIMEOUT.
					Log.d(TAG, "(S) TIMEOUT waiting <DATA_ACK> or <END> from "
							+ clientIP);

					attempts++;
					if (attempts > 3) { // Se alcanzo el maximo de intentos.
						// Fin streaming. Se cambia de estado.
						synchronized (state) {
							state = ServerState.END;
						}
					}
				} else if (rMessage.getIp().equalsIgnoreCase(clientIP)) {
					if (rMessage.getType() == MessageType.DATA_ACK) { // DATA_ACK.
						Log.d(TAG, "(S) <DATA_ACK> received from " + clientIP);

						// Se reinicia el numero de intentos.
						attempts = 1;

						// Se realiza una espera de 0.8 sg hasta el envio del
						// siguiente mensaje DATA.
						try {
							Thread.sleep(800);
						} catch (InterruptedException e) {
							Log.d(TAG,
									"(S) mThread interrupted: "
											+ e.getMessage());
							e.printStackTrace();
						}
					} else { // END.
						Log.d(TAG, "(S) <END> received from " + clientIP);

						// Fin streaming. Se cambia de estado.
						synchronized (state) {
							state = ServerState.END;
						}
						attempts = 4;
					}
				} else { // El mensaje no es del cliente. Posible ataque.
					attempts++;
					Log.d(TAG, "(S) Message from an unknown client: "
							+ rMessage.getIp());
				}
			} while ((attempts <= 3) && (!thisThread.isInterrupted())
					&& (thisThread == dThread));
		}

		/**
		 * Implementa el comportamiento del Servidor en el estado END.
		 */
		private void doEnd() {
			Log.d(TAG, "(S) Streaming session ended with: " + clientIP);
			addInfoToLog("Streaming session ended with: " + clientIP);

			// Se limpia la cola de mensajes.
			messageQueue.clearQueue();

			// Se limpian las variables globales del hilo.
			rMessage = null;
			sMessage = null;
			clientOK = false;
			clientIP = "";

			// Se restablece el sonido del dispositivo.
			if (soundState != -1) { // Estado modificado.
				AppContext
						.changeSoundState(getApplicationContext(), soundState);
			}

			// Se vuelve al estado inicial.
			synchronized (state) {
				state = ServerState.NOTIFY;
			}
			Log.d(TAG, "(S) Return to the initial state");
		}

		/**
		 * Indica si un cliente es valido o no para el servicio de streaming,
		 * evaluando su localizacion respecto a la del dispositivo servidor.
		 * 
		 * @param clientLocation
		 *            Localizacion cliente (Vector 2D)
		 * @return Cliente valido-True / Cliente no valido-False
		 */
		private boolean isValidClient(double[] clientLoc) {
			// Se comprueba si la validacion esta desactivada.
			if (valParams[2] == 1) {
				// Se acepta por defecto.
				return true;
			}
			// Para que sea un cliente valido debe ir en la misma direccion que
			// el servidor y detras de el.
			double[] clientL = clientLoc;
			double[] serverL = getLocation();
			if ((lastLocation[0] != null) && (lastLocation[1] != null)) { // LOCALIZACION-DISPONIBLE.
				// Se valida que ambos vectores cuenten con 2 puntos distintos,
				// es decir que no tengan modulo nulo.
				if (AppContext.isOnePointVector(serverL)
						|| AppContext.isOnePointVector(clientL)) {
					// No se puede continuar con la validacion.
					Log.d(TAG_2, "(S) One point vector");
					addInfoToLog("(VAL) One point vector");
					return false;
				} else {
					// Validacion misma direccion-sentido. Se evalua el angulo
					// que forman los dos vectores.
					double degrees = AppContext.getDegrees(serverL, clientL);
					if (degrees <= valParams[0]) { // Misma direccion-sentido.
						// Validacion cliente detras de servidor. Se compara el
						// angulo formado por el vector de direccion del
						// servidor en sentido contrario y el vector que une la
						// posicion actual del servidor con la del cliente.
						serverL[0] = lastLocation[1].getLongitude();
						serverL[1] = lastLocation[1].getLatitude();
						serverL[2] = lastLocation[0].getLongitude();
						serverL[3] = lastLocation[0].getLatitude();
						double[] serverToClient = { serverL[0], serverL[1],
								clientLoc[2], clientLoc[3] };
						degrees = AppContext
								.getDegrees(serverL, serverToClient);
						if (degrees <= valParams[1]) { // Detras.
							return true;
						} else { // No detras.
							Log.d(TAG_2,
									"(S) Client isn't behind of the server - "
											+ Double.toString(degrees)
											+ " degrees");
							addInfoToLog("(VAL) Client isn't behind of the server");
							return false;
						}
					} else { // Diferente direccion-sentido.
						Log.d(TAG_2,
								"(S) Different direction - "
										+ Double.toString(degrees) + " degrees");
						addInfoToLog("(VAL) Different directions");
						return false;
					}
				}
			} else { // LOCALIZACION-NO-DISPONIBLE.
				// No se puede efectuar la validacion.
				Log.d(TAG_2, "(S) Location not available");
				addInfoToLog("(VAL) Location not available");
				return false;
			}
		}

		/**
		 * Permite enviar un mensaje a un dispositivo cliente indicando su
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
				Log.d(TAG, "(S) Error packaging message: " + e.getMessage());
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
			db.execSQL("CREATE TABLE IF NOT EXISTS serverLog (id INTEGER PRIMARY KEY autoincrement, time TEXT NOT NULL, info TEXT NOT NULL)");
			db.execSQL("INSERT INTO serverLog VALUES(null,'[ "
					+ AppContext.getSystemTime() + " ]','" + info + "')");
			db.close();
		}

	} // Fin clase interna 'ServerDiagramThread'

} // Fin clase 'SOASServer'
