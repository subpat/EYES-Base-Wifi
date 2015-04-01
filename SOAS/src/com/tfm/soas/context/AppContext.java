package com.tfm.soas.context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.http.conn.util.InetAddressUtils;

import com.tfm.soas.logic.SOASMessage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Clase que contiene objetos y metodos comunes a toda la aplicacion para que
 * puedan ser utilizados por varias clases diferentes con facilidad.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 27-05-2014
 */
@SuppressLint("NewApi") public final class AppContext {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	public static final int RECEIVE_SERVER_PORT = 7777; // Puerto recepciones S.
	public static final int RECEIVE_CLIENT_PORT = 8888; // Puerto recepciones C.
	private static final String TAG = "SOASContext";

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	public static Toast toast = null; // Mensajes para usuario.

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Devuelve la direccion IP de la primera interfaz diferente a localhost.
	 * 
	 * @param useIPv4
	 *            true ipv4 / false ipv6
	 * @return Direccion IP o string vacio.
	 */
	public static String getIPAddress(boolean useIPv4) {
		try {
			// Se obtiene la lista de interfaces del dispositivo.
			List<NetworkInterface> interfaces = Collections
					.list(NetworkInterface.getNetworkInterfaces());
			for (NetworkInterface intf : interfaces) {
				// Se obtiene la lista de direcciones de esta interfaz.
				List<InetAddress> addrs = Collections.list(intf
						.getInetAddresses());
				for (InetAddress addr : addrs) {
					if (!addr.isLoopbackAddress()) {
						String sAddr = addr.getHostAddress().toUpperCase();
						boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
						if (useIPv4) { // IPv4
							if (isIPv4)
								return sAddr;
						} else { // IPv6
							if (!isIPv4) {
								int delim = sAddr.indexOf('%');
								return delim < 0 ? sAddr : sAddr.substring(0,
										delim);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			// No se pudo obtener la direccion IP.
			Log.d(TAG, "Error obtaining IP: " + e.getMessage());
		}
		return "";
	}

	/**
	 * Devuelve las dimensiones (Width-Height) de la pantalla del dispositivo.
	 * 
	 * @param cntx
	 *            Contexto app
	 * @return Elemento 0 - Width / Elemento 1 - Height
	 */
	public static int[] getScreenDimensions(Context cntx) {
		// Variables locales.
		int[] dimens = { 0, 0 };

		// Se recuperan la preferencias para ver si las dimensiones ya fueron
		// guardadas.
		SharedPreferences prefs = cntx.getSharedPreferences("SOAS_prefs",
				Context.MODE_PRIVATE);
		String dim = prefs.getString("dimens", "");

		if (dim == "") { // NO GUARDADAS
			DisplayMetrics dm = new DisplayMetrics();
			WindowManager wm = (WindowManager) cntx
					.getSystemService(Context.WINDOW_SERVICE);

			// Se obtienen las dimensiones de la pantalla del dispositivo en px.
			if (android.os.Build.VERSION.SDK_INT >= 17) {
				wm.getDefaultDisplay().getRealMetrics(dm);
			} else {
				wm.getDefaultDisplay().getMetrics(dm);
			}
			dimens[0] = dm.widthPixels;
			dimens[1] = dm.heightPixels;

			// Se guardan en preferencias.
			SharedPreferences.Editor editor = prefs.edit();
			editor.putString("dimens", dimens[0] + "x" + dimens[1]);
			editor.commit();
		} else { // GUARDADAS.
			dimens[0] = Integer.parseInt(dim.split("x")[0]);
			dimens[1] = Integer.parseInt(dim.split("x")[1]);
		}

		return dimens;
	}

	/**
	 * Permite serializar un mensaje SOAS convirtiendolo en un conjunto de bytes
	 * capaces de ser enviados a traves de la red.
	 * 
	 * @param message
	 *            Mensaje a serializar
	 * @return Mensaje serializado
	 */
	public static byte[] serializeMessage(SOASMessage message) {
		try {
			ByteArrayOutputStream bs = new ByteArrayOutputStream();
			ObjectOutputStream os = new ObjectOutputStream(bs);
			os.writeObject(message);
			os.close();
			return bs.toByteArray();
		} catch (Exception e) {
			Log.d(TAG, "Error serializing the message: " + e.getMessage());
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Permite reconstruir un mensaje SOAS previamente serializado y convertido
	 * en un conjunto de bytes.
	 * 
	 * @param bytes
	 *            Mensaje serializado
	 * @return Mensaje reconstruido
	 */
	public static SOASMessage deserializeMessage(byte[] bytes) {
		try {
			ByteArrayInputStream bs = new ByteArrayInputStream(bytes);
			ObjectInputStream is = new ObjectInputStream(bs);
			SOASMessage message = (SOASMessage) is.readObject();
			is.close();
			return message;
		} catch (Exception e) {
			Log.d(TAG, "Error deserializing the message: " + e.getMessage());
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Devuelve el estado del sonido del dispositivo.
	 * 
	 * @param cntx
	 *            Contexto App
	 * @return Estado
	 */
	public static int soundState(Context cntx) {
		AudioManager am = (AudioManager) cntx
				.getSystemService(Context.AUDIO_SERVICE);
		return am.getRingerMode();
	}

	/**
	 * Permite modificar el estado del sonido del dispositivo.
	 * 
	 * @param cntx
	 *            Contexto App
	 * @param state
	 *            NORMAL/SILENT/VIBRATE
	 */
	public static void changeSoundState(Context cntx, int state) {
		AudioManager am = (AudioManager) cntx
				.getSystemService(Context.AUDIO_SERVICE);
		am.setRingerMode(state);
	}

	/**
	 * Devuelve la hora del sistema en el formato HH:mm:ss.SSS
	 */
	public static String getSystemTime() {
		Date now = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
		return sdf.format(now);
	}

	/**
	 * Permite saber si un vector 2D posee dos puntos completamente iguales y
	 * que por tanto tiene modulo nulo.
	 * 
	 * @param vector
	 *            Vector a validar. Punto A: Elementos 0,1 / Punto B: Elementos
	 *            2,3
	 * @return True-Un punto / False-Dos puntos
	 */
	public static boolean isOnePointVector(double[] vector) {
		if ((vector[0] == vector[2]) && (vector[1] == vector[3])) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Permite mover un vector 2D a una determinada posicion dentro del plano
	 * cartesiano.
	 * 
	 * @param vector
	 *            Vector a desplazar. Punto A: Elementos 0,1 / Punto B:
	 *            Elementos 2,3
	 * @param position
	 *            Posicion donde colocar el vector
	 */
	public static double[] moveVector(double[] vector, double[] position) {
		// Se calculan los desplazamientos que deben sufrir los puntos del
		// vector.
		double xMovement = position[0] - vector[0];
		double yMovement = position[1] - vector[1];

		// Se obtiene el vector desplazado.
		double[] vd = { 0, 0, 0, 0 };
		vd[0] = vector[0] + xMovement;
		vd[1] = vector[1] + yMovement;
		vd[2] = vector[2] + xMovement;
		vd[3] = vector[3] + yMovement;

		return vd;
	}

	/**
	 * Permite obtener el angulo formado por dos vectores 2D.
	 * 
	 * @param u
	 *            Vector 1 Punto A: Elementos 0,1 / Punto B: Elementos 2,3
	 * @param v
	 *            Vector 2 Punto A: Elementos 0,1 / Punto B: Elementos 2,3
	 * @return Angulo entre vectores
	 */
	public static double getDegrees(double[] u, double[] v) {
		// Se mueven los vectores al origen de coordenadas.
		double[] origin = { 0, 0 };
		u = moveVector(u, origin);
		v = moveVector(v, origin);

		// Se calculan por separado el numerador y el denominador de la formula
		// para el calculo del angulo entre dos vectores.
		double numerator = (u[2] * v[2]) + (u[3] * v[3]);
		double denominator = Math.hypot(u[2], u[3]) * Math.hypot(v[2], v[3]);

		// Se calcula el cociente. Si es necesario se ajustará para que no se
		// produzcan valores no numericos.
		double quotient = (numerator / denominator);
		if (quotient > 1) {
			quotient = 1;
		} else if (quotient < -1) {
			quotient = -1;
		}

		// Se calcula el angulo final.
		double radians = Math.acos(quotient);
		double degrees = Math.toDegrees(radians);
		if (degrees < 0) { // Angulo en positivo.
			degrees *= -1;
		}

		return degrees;
	}

} // Fin clase 'AppContext'
