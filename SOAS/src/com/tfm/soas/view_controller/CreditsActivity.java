package com.tfm.soas.view_controller;

import com.tfm.soas.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;

/**
 * Actividad que muestra informacion acerca de la aplicacion con un aspecto de
 * dialogo.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 25-08-2014
 */
public class CreditsActivity extends Activity {

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
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Se establece la interfaz de la actividad.
		setContentView(R.layout.activity_credits);
		getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT);
	}

} // Fin clase 'CreditsActivity'
