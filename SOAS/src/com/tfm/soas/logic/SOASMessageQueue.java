package com.tfm.soas.logic;

import java.util.LinkedList;


import android.util.Log;

/**
 * Cola de mensajes SOAS que garantiza la exclusion mutua en la lectura y
 * escritura de mensajes por parte de los threads consumidor y productor.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 16-06-2014
 */
public class SOASMessageQueue {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	private static final int MAX_SIZE = 30; // Tamanyo maximo.
	private static final String TAG = "SOASMessageQueue";

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private LinkedList<SOASMessage> messageQueue = null; // Buffer de mensajes.
	private boolean isEmpty = true; // Esta vacia.
	private boolean isFull = false; // Esta llena.

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Constructor para instancias de la clase SOASMessageQueue.
	 */
	public SOASMessageQueue() {
		// Se inicializa la cola de mensajes.
		messageQueue = new LinkedList<SOASMessage>();
		isEmpty = true;
		isFull = false;
	}

	/**
	 * Devuelve y elimina el mensaje situado en la primera posicion de la cola.
	 * 
	 * @param timeout
	 *            Tiempo de espera si la cola esta vacia
	 * @return Mensaje situado en la cabeza de la cola
	 * @throws InterruptedException
	 */
	public synchronized SOASMessage takeMessage(long timeout) {
		// No se puede consumir si la cola esta vacia.
		while (isEmpty) {
			// Se espera a que la cola deje de estar vacia o a que se
			// consuma el timeout.
			try {
				wait(timeout);
				if ((isEmpty) && (timeout > 0)) { // Vencio el timeout.
					return null;
				}
			} catch (InterruptedException e) {
				Log.d(TAG, "Error waiting for a message: " + e.getMessage());
				e.printStackTrace();

				// Se retransmite la interrupcion.
				Thread.currentThread().interrupt();

				// Se interrumpio el hilo, se devuelve un mensaje
				// vacio.
				return new SOASMessage();
			}
		}
		// Se extrae el mensaje.
		SOASMessage message = messageQueue.poll();

		// Se actualiza el estado de la cola.
		isEmpty = messageQueue.isEmpty();
		isFull = false;

		// Despierta al productor si se encuentra bloqueado.
		notify();

		// Devuelve el mensaje al thread consumidor.
		return (message);
	}

	/**
	 * Inserta un mensaje al final de la cola.
	 * 
	 * @param message
	 *            Mensaje a insertar
	 * @throws InterruptedException
	 */
	public synchronized void insertMessage(SOASMessage message) {
		// No se puede introducir si la cola esta llena.
		while (isFull) {
			// Se espera a que la cola deje de estar llena.
			try {
				wait(0);
			} catch (InterruptedException e) {
				Log.d(TAG, "Error waiting for a gap: " + e.getMessage());
				e.printStackTrace();

				// Se retransmite la interrupcion.
				Thread.currentThread().interrupt();

				return;
			}
		}
		// Se inserta el mensaje al final de la cola.
		messageQueue.addLast(message);

		// Se actualiza el estado de la cola.
		isEmpty = false;
		if (messageQueue.size() == MAX_SIZE) {
			isFull = true;
		}

		// Despierta al consumidor si se encuentra bloqueado.
		notify();
	}

	/**
	 * Vacia la cola de mensajes.
	 */
	public synchronized void clearQueue() {
		messageQueue.clear();
		isEmpty = true;
		isFull = false;
	}

} // Fin clase 'SOASMessageQueue'
