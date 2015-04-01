package com.tfm.soas.view_controller;

import java.lang.ref.WeakReference;

import org.videolan.libvlc.EventHandler;
import org.videolan.libvlc.IVideoPlayer;
import org.videolan.libvlc.LibVLC;

import com.tfm.soas.R;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.ViewGroup.LayoutParams;
import android.widget.TextView;

/**
 * Actividad que permite reproducir un streaming de video RTSP haciendo uso de
 * la libreria libVLC.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 27-05-2014
 */
public class RTSPPlayerActivity extends Activity implements SurfaceHolder.Callback,
		IVideoPlayer {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	private static final int VideoSizeChanged = -1;
	private static final String TAG = "RTSPPlayerActivity";

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private String streamingPath = null; // Ruta Servidor RTSP.
	private LibVLC libvlc = null; // Reproductor de streaming RTSP.
	private SurfaceView surface = null; // Area de visualizacion.
	private SurfaceHolder holder = null; // Contenedor del area.
	private int videoWidth; // Ancho video.
	private int videoHeight; // Alto video.
	private Handler handler = null; // Oyente de eventos del video.
	private PlayerReceiver pReceiver = null; // Receptor eventos externos.

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/*----------*
	 | ACTIVITY |
	 *----------*/
	/**
	 * Inicializa la actividad.
	 * 
	 * @param savedInstanceState
	 *            Si la actividad esta siendo re-inicializada este Bundle
	 *            contiene informacion reciente acerca de su anterior estado
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Se carga la interfaz de la actividad.
		setContentView(R.layout.activity_rtsp_player);

		// Se obtiene la ruta al servidor RTSP.
		Intent intent = getIntent();
		streamingPath = intent.getExtras().getString("rtsp_server_url");
		Log.d(TAG, "(P) Server URL: " + streamingPath);

		// Se obtienen las referencias a la superficie de visualizacion y a su
		// contenedor.
		surface = (SurfaceView) findViewById(R.id.surface);
		holder = surface.getHolder();
		holder.addCallback(this);

		// Se añaden los flags necesarios para despertar al movil si estuviera
		// en standby y para mantener la pantalla encendida mientras se
		// reproduce el video.
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		// Se instancia el BroadcastReceiver que es avisado cuando finaliza la
		// sesion de streaming.
		pReceiver = new PlayerReceiver();

		// Se crea el oyente de eventos del video.
		handler = new VideoHandler(this);
	}

	/**
	 * Permite configurar los elementos que componen la actividad antes de que
	 * esta pase a primer plano.
	 */
	@Override
	protected void onResume() {
		super.onResume();

		// Se registra el BroadcastReceiver que detecta el fin de la sesion
		// RTSP.
		registerReceiver(pReceiver, new IntentFilter("player_Receiver"));

		// Se crea el reproductor y se arranca la reproduccion.
		createPlayer(streamingPath);
	}

	/**
	 * Permite liberar los recursos utilizados por la actividad antes de que
	 * esta pase a segundo plano.
	 */
	@Override
	protected void onPause() {
		super.onPause();

		// Se libera el reproductor.
		releasePlayer();
		Log.d(TAG, "(P) Disconnected from RTSP Server");

		// Se elimina el BroadcastReceiver que se registro para detectar el fin
		// de la sesion RTSP.
		unregisterReceiver(pReceiver);

		// Se avisa al servicio cliente de la parada de la reproduccion.
		sendBroadcast(new Intent("stop_Play"));

		// Se cierra la actividad, pues su objetivo es mostrar video en tiempo
		// real y para ello requiere primer plano.
		finish();
	}

	/**
	 * Este metodo es llamado cuando la configuracion del dispositivo cambia
	 * mientras la actividad se encuentra ejecutandose.
	 * 
	 * @param newConfig
	 *            Nueva configuracion
	 */
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);

		// Se ajustan las dimensiones del area de video.
		setSize(videoWidth, videoHeight);
	}

	/*---------*
	 | SURFACE |
	 *---------*/
	/**
	 * Se ejecuta inmediatamente despues de que la SurfaceView se haya creado
	 * correctamente.
	 * 
	 * @param holder
	 *            El SurfaceHolder que contiene a la Surface que se ha creado
	 * 
	 */
	public void surfaceCreated(SurfaceHolder holder) {
	}

	/**
	 * Se ejecuta inmediatamente despues de que la SurfaceView haya sufrido
	 * alguna modificacion.
	 * 
	 * @param holder
	 *            El SurfaceHolder que contiene a la Surface que ha cambiado
	 * @param format
	 *            El nuevo PixelFormat de la Surface
	 * @param w
	 *            El nuevo ancho de la Surface
	 * @param h
	 *            El nuevo alto de la Surface
	 * 
	 */
	public void surfaceChanged(SurfaceHolder surfaceholder, int format,
			int width, int height) {
		// Se indica al reproductor VLC la surface donde se reproduce el
		// video.
		if (libvlc != null)
			libvlc.attachSurface(holder.getSurface(), this);
	}

	/**
	 * Se ejecuta inmediatamente antes de que la SurfaceView sea destruida.
	 * 
	 * @param holder
	 *            El SurfaceHolder que contiene a la Surface que se va a
	 *            destruir
	 * 
	 */
	public void surfaceDestroyed(SurfaceHolder surfaceholder) {
	}

	/**
	 * Permite ajustar las dimensiones del area de visualizacion de video.
	 * 
	 * @param width
	 * @param height
	 */
	private void setSize(int width, int height) {
		// Se guardan las dimensiones
		videoWidth = width;
		videoHeight = height;

		if (videoWidth * videoHeight <= 1)
			return;

		// Se obtienen las dimensiones de la pantalla.
		int w = getWindow().getDecorView().getWidth();
		int h = getWindow().getDecorView().getHeight();

		// El metodo getWindow().getDecorView() no siempre tiene en cuenta la
		// orientacion, se deben corregir los valores.
		boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
		if (w > h && isPortrait || w < h && !isPortrait) {
			int i = w;
			w = h;
			h = i;
		}

		// Se calculan los Aspect Ratios para ajustar las dimensiones.
		float videoAR = (float) videoWidth / (float) videoHeight;
		float screenAR = (float) w / (float) h;
		if (screenAR < videoAR)
			h = (int) (w / videoAR);
		else
			w = (int) (h * videoAR);

		// Se fuerza el buffer de dimensiones de la surface.
		holder.setFixedSize(videoWidth, videoHeight);

		// Se establecen las dimensiones de visualizacion.
		LayoutParams lp = surface.getLayoutParams();
		lp.width = w;
		lp.height = h;
		surface.setLayoutParams(lp);
		surface.invalidate();
	}

	/**
	 * Este metodo es llamado cuando se produce un cambio de orientacion del
	 * dispositivo y es necesario un reajuste del tamaño de la surface, debido a
	 * que el tamaño de los frames a visualizar no encajan dentro del tamaño de
	 * la surface.
	 * 
	 * @param width
	 *            Ancho frame
	 * @param height
	 *            Alto frame
	 * @param visible_width
	 *            Ancho visible frame
	 * @param visible_height
	 *            Alto visible frame
	 * @param sar_num
	 *            Numerador aspect ratio de la surface
	 * @param sar_den
	 *            Denominador aspect ratio de la surface
	 */
	@Override
	public void setSurfaceSize(int width, int height, int visible_width,
			int visible_height, int sar_num, int sar_den) {
		Message msg = Message.obtain(handler, VideoSizeChanged, width, height);
		msg.sendToTarget();
	}

	/**
	 * Permite limpiar la superficie de visualizacion.
	 */
	private void clearSurface() {
		if (holder != null) {
			holder.setFormat(PixelFormat.TRANSPARENT);
			holder.setFormat(PixelFormat.OPAQUE);
		}
	}

	/*--------*
	 | PLAYER |
	 *--------*/
	/**
	 * Permite crear un nuevo reproductor multimedia VLC conectado al servidor
	 * RTSP.
	 * 
	 * @param media
	 *            Ruta al servidor RTSP
	 */
	private void createPlayer(String media) {
		// Si existiera una instancia previa se elimina.
		releasePlayer();
		try {
			// Se crea un nuevo reproductor multimedia.
			libvlc = LibVLC.getInstance();
			libvlc.setHardwareAcceleration(LibVLC.HW_ACCELERATION_DISABLED);
			libvlc.setSubtitlesEncoding("");
			libvlc.setAout(LibVLC.AOUT_OPENSLES);
			libvlc.setTimeStretching(true);
			libvlc.setChroma("RV32");
			libvlc.setVerboseMode(true);
			libvlc.setNetworkCaching(400);
			LibVLC.restart(this);
			EventHandler.getInstance().addHandler(handler);
			holder.setFormat(PixelFormat.RGBX_8888);
			holder.setKeepScreenOn(true);
			libvlc.playMRL(media);

		} catch (Exception e) {
			Log.d(TAG, "(P) Error creating VLC media player: " + e.getMessage());
			e.printStackTrace();

			// Se cierra la actividad.
			finish();
		}
	}

	/**
	 * Permite eliminar el reproductor multimedia VLC.
	 */
	private void releasePlayer() {
		if (libvlc == null) {
			return;
		} else {
			EventHandler.getInstance().removeHandler(handler);
			libvlc.stop();
			libvlc.detachSurface();
			libvlc.closeAout();
			libvlc.destroy();
			libvlc = null;

			videoWidth = 0;
			videoHeight = 0;
		}
	}

	/*--------------------------------------------------------*/
	/* /////////////////// CLASES INTERNAS ////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Oyente encargado de gestionar los eventos que se producen al reproducir
	 * el streaming de video RTSP.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 14-04-2014
	 */
	private class VideoHandler extends Handler {

		/*-----------*/
		/* ATRIBUTOS */
		/*-----------*/
		private WeakReference<RTSPPlayerActivity> owner;
		private int connectionAttempts = 1;

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Constructor para instancias de la clase 'VideoHandler'.
		 * 
		 * @param owner
		 *            Propietario donde se originan los eventos
		 */
		public VideoHandler(RTSPPlayerActivity owner) {
			this.owner = new WeakReference<RTSPPlayerActivity>(owner);
		}

		/**
		 * Este metodo permite gestionar los distintos eventos que recibe el
		 * oyente.
		 * 
		 * @param msg
		 *            Mensaje con info del evento ocurrido
		 */
		@Override
		public void handleMessage(Message msg) {
			RTSPPlayerActivity player = owner.get();

			// Evento por cambio de dimensiones.
			if (msg.what == VideoSizeChanged) {
				player.setSize(msg.arg1, msg.arg2);
				return;
			}

			// Eventos LibVLC.
			Bundle b = msg.getData();
			switch (b.getInt("event")) {
			case EventHandler.MediaPlayerEndReached:
				// Si se alcanzo el fin del video es porque el servidor RTSP no
				// esta respondiendo.
				Log.d(TAG, "(P) The RTSP Server is not responding");

				// Se libera el reproductor.
				player.releasePlayer();

				// Se cierra la actividad.
				finish();
				break;
			case EventHandler.MediaPlayerEncounteredError:
				// No ha sido posible conectarse correctamente al servidor RTSP.
				Log.d(TAG,
						"(P) Unable to establish connection with RTSP server");
				if (connectionAttempts <= 3) {
					// Se espera 0,3 sg y se intenta reconectar con el servidor
					// RTSP.
					try {
						Thread.sleep(300);
						connectionAttempts++;
						player.clearSurface();
						player.createPlayer(streamingPath);
					} catch (InterruptedException e) {
						Log.d(TAG,
								"(P) Thread interrupted while attempting to connect to RTSP server.");
						e.printStackTrace();
					}

				} else {
					// Se libera el reproductor.
					player.releasePlayer();

					// Se cierra la actividad.
					finish();
				}
				break;
			case EventHandler.MediaPlayerPlaying:
				// Conexion establecida correctamente con el servidor RTSP.
				Log.d(TAG, "(P) Connected to RTSP server");
				connectionAttempts = 1;
				break;
			case EventHandler.MediaPlayerPaused:
				break;
			case EventHandler.MediaPlayerStopped:
				break;
			default:
				break;
			}
		}

	} // Fin clase interna 'VideoHandler'

	/**
	 * BroadcastReceiver que recibe dos tipos de notificaciones:
	 * 
	 * 1- Ante la finalizacion de la sesion de streaming. En ese caso debe
	 * cerrar el reproductor de video RTSP.
	 * 
	 * 2- Ante una actualizacion de la velocidad del servidor.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 06-07-2014
	 */
	private class PlayerReceiver extends BroadcastReceiver {

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
			if (intent.hasExtra("speed")) { // Actualizacion Velocidad.
				String speed = intent.getExtras().getString("speed");
				TextView tv = (TextView) findViewById(R.id.speed_Text);
				tv.setText(speed);
			} else { // Cierre Player.
				finish();
			}
		}

	} // Fin clase interna 'PlayerReceiver'

} // Fin clase 'RTSPPlayerActivity'
