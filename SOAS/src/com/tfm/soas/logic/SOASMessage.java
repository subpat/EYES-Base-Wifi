package com.tfm.soas.logic;

import java.io.Serializable;

/**
 * Clase que implementa los mensajes utilizados por el sistema en su protocolo
 * de comunicacion C-S. Existen 7 tipos de mensajes diferentes:
 * 
 * 1- HELLO (S): Enviado por el servidor para anunciar a los clientes cercanos
 * el servicio de streaming que ofrece. [CONTENIDO: Ubicacion, Direccion y
 * Sentido]
 * 
 * 2- REQUEST (C): Enviado por el cliente para solicitar la conexion al servicio
 * de streaming ofrecido previamente por un servidor. [CONTENIDO: Ubicacion,
 * Direccion, Sentido y Resolucion maxima soportada]
 * 
 * 3- READY (S): Enviado por el servidor para indicar a un cliente que puede
 * conectarse al servicio de Streaming. [CONTENIDO: Puerto RTSP, Ubicacion,
 * Direccion, Sentido y Velocidad]
 * 
 * 4- REJECT (S): Enviado por el servidor para indicar a un cliente que rechaza
 * su peticion de conexion. [CONTENIDO: ---]
 * 
 * 5- DATA (S): Enviado por el servidor para indicar a un cliente su vector
 * desplazamiento y su velocidad durante la sesion de streaming de video.
 * [CONTENIDO: Ubicacion, Direccion, Sentido y Velocidad]
 * 
 * 6- DATA_ACK (C): Enviado por el cliente como respuesta a un mensaje DATA para
 * que el servidor sepa que la conexion sigue viva. [CONTENIDO: ---]
 * 
 * 7- END (C): Enviado por el cliente para indicar al servidor el fin de la
 * sesion de streaming. [CONTENIDO: ---]
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 27-05-2014
 */
public class SOASMessage implements Serializable {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	public static enum MessageType {
		HELLO, REQUEST, READY, REJECT, DATA, DATA_ACK, END
	}

	private static final long serialVersionUID = 1L;

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private MessageType type = null; // Tipo mensaje.
	private String ip = ""; // Direccion IP del emisor.
	private double location[] = new double[4]; // Ubicacion-Direccion-Sentido.
	private float speed = 0; // Velocidad.
	private int maxResolution[] = new int[2]; // Resolucion max soportada.
	private int rtspPort = 0; // Puerto de escucha servidor RTSP.

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Devuelve el tipo de mensaje.
	 * 
	 * @return Tipo
	 */
	public MessageType getType() {
		return type;
	}

	/**
	 * Devuelve la direccion IP del emisor.
	 * 
	 * @return IP
	 */
	public String getIp() {
		return ip;
	}

	/**
	 * Devuelve un vector 2D (AB) que marca el sentido, direccion y ubicacion
	 * actual del dispositivo emisor. Punto A: Elementos 0,1 / Punto B:
	 * Elementos 2,3.
	 * 
	 * @return Localizacion
	 */
	public double[] getLocation() {
		return location;
	}

	/**
	 * Devuelve la velocidad a la que se desplaza el dispositivo emisor.
	 * 
	 * @return Velocidad en Km/h
	 */
	public float getSpeed() {
		return speed;
	}

	/**
	 * Devuelve la maxima resolucion de reproduccion de video soportada por el
	 * dispositivo emisor. Ancho: Elemento 0 / Alto: Elemento 1
	 * 
	 * @return Maxima resolucion. Ancho x Alto
	 */
	public int[] getMaxResolution() {
		return maxResolution;
	}

	/**
	 * Devuelve el puerto donde escucha el servidor RTSP en el dispositivo
	 * emisor.
	 * 
	 * @return Puerto RTSP
	 */
	public int getRTSPPort() {
		return rtspPort;
	}

	/**
	 * Permite definir el tipo de mensaje.
	 * 
	 * @param type
	 *            Tipo de mensaje
	 */
	public void setType(MessageType type) {
		this.type = type;
	}

	/**
	 * Permite definir la direccion IP del emisor.
	 * 
	 * @param ip
	 *            IP emisor
	 */
	public void setIp(String ip) {
		this.ip = ip;
	}

	/**
	 * Permite definir la localizacion del dispositivo emisor a traves de un
	 * vector 2D (AB) que marca el sentido, direccion y ubicacion actual del
	 * dispositivo emisor. Punto A: Elementos 0,1 / Punto B: Elementos 2,3.
	 * 
	 * @param location
	 *            Localizacion
	 */
	public void setLocation(double[] location) {
		this.location = location;
	}

	/**
	 * Permite definir la velocidad a la que se desplaza el dispositivo emisor.
	 * 
	 * @param speed
	 *            Velocidad en Km/h
	 */
	public void setSpeed(float speed) {
		this.speed = speed;
	}

	/**
	 * Permite definir la maxima resolucion de reproduccion de video soportada
	 * por el dispositivo emisor. Ancho: Elemento 0 / Alto: Elemento 1
	 * 
	 * @param maxResolution
	 *            Maxima resolucion. Ancho x Alto
	 */
	public void setMaxResolution(int[] maxResolution) {
		this.maxResolution = maxResolution;
	}

	/**
	 * Permite definir el puerto donde escucha el servidor RTSP en el
	 * dispositivo emisor.
	 * 
	 * @param portRTSP
	 *            Puerto RTSP
	 */
	public void setRTSPPort(int portRTSP) {
		this.rtspPort = portRTSP;
	}

} // Fin clase 'SOASMessage'
