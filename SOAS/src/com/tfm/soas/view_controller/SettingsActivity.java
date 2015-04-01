package com.tfm.soas.view_controller;

import java.util.HashMap;
import java.util.Map;

import com.tfm.soas.R;
import com.tfm.soas.context.AppContext;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.CompoundButton.OnCheckedChangeListener;

/**
 * Actividad que permite configurar los parametros de validacion empleados por
 * el sistema (SOAS).
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 25-08-2014
 */
public class SettingsActivity extends Activity {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	public static final int DEFAULT_DIR_DEGREES = 20;
	public static final int DEFAULT_LOC_DEGREES = 5;
	public static final int DEFAULT_OVER_DEGREES = 90;

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private CheckBox disableBtn = null; // Boton para deshabilitar validacion.
	private ImageButton saveBtn = null; // Boton para guardar parametros.
	private ImageButton restoreBtn = null; // Boton para restablecer parametros.
	private EditText dirDegrees = null; // Grados direccion.
	private EditText locDegrees = null; // Grados ubicacion.
	private EditText overDegrees = null; // Grados adelantamiento.

	/*--------------------------------------------------------*/
	/* /////////////////////// METODOS ////////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Inicializa la actividad.
	 * 
	 * @param savedInstanceState
	 *            Si la actividad esta siendo re-inicializada este Bundle
	 *            contiene informacion reciente acerca de su anterior estado.
	 */
	@SuppressLint("ShowToast")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Se carga la interfaz de la actividad.
		setContentView(R.layout.activity_settings);

		// Se recupera una referencia a los botones de la actividad y se asigna
		// un oyente a los que lo precisen.
		dirDegrees = (EditText) findViewById(R.id.direction_value);
		locDegrees = (EditText) findViewById(R.id.location_value);
		overDegrees = (EditText) findViewById(R.id.overtaking_value);
		disableBtn = (CheckBox) findViewById(R.id.disable_checkBox);
		disableBtn.setOnCheckedChangeListener(new CheckBoxListener());
		saveBtn = (ImageButton) findViewById(R.id.save_button);
		restoreBtn = (ImageButton) findViewById(R.id.restore_button);
		SettingsBtnListener btnListener = new SettingsBtnListener();
		saveBtn.setOnClickListener(btnListener);
		restoreBtn.setOnClickListener(btnListener);

		// Se inicializa el toast para mostrar mensajes si fue destruido.
		if (AppContext.toast == null) {
			AppContext.toast = Toast.makeText(getApplicationContext(), "",
					Toast.LENGTH_SHORT);
		}
	}

	/**
	 * Permite configurar los elementos que componen la actividad antes de que
	 * esta pase a primer plano.
	 */
	@Override
	protected void onResume() {
		super.onResume();

		// Se restablecen los ajustes guardados.
		updateSettings();
	}

	/**
	 * Permite restablecer los ajustes guardados en preferencias.
	 */
	private void updateSettings() {
		SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
				Context.MODE_PRIVATE);
		dirDegrees.setText(String.valueOf(prefs.getInt("direction",
				DEFAULT_DIR_DEGREES)));
		locDegrees.setText(String.valueOf(prefs.getInt("location",
				DEFAULT_LOC_DEGREES)));
		overDegrees.setText(String.valueOf(prefs.getInt("overtaking",
				DEFAULT_OVER_DEGREES)));
		if (prefs.getInt("disable_val", 0) == 1) {
			disableBtn.setChecked(true);
		} else {
			disableBtn.setChecked(false);
		}
	}

	/*--------------------------------------------------------*/
	/* /////////////////// CLASES INTERNAS ////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Oyente encargado de gestionar los cambios de estado del boton que
	 * habilita/deshabilita la validacion.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 26-08-2014
	 */
	private class CheckBoxListener implements OnCheckedChangeListener {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Se ejecuta cuando el CheckBox cambia de estado.
		 * 
		 * @param buttonView
		 *            CompoundButton que ha cambiado de estado
		 * @param isChecked
		 *            True-ON / False-OFF
		 */
		@Override
		public void onCheckedChanged(CompoundButton buttonView,
				boolean isChecked) {
			// El estado del CheckBox se guarda de forma persistente.
			SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
					Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = prefs.edit();

			if (isChecked) { // ON.
				dirDegrees.setEnabled(false);
				locDegrees.setEnabled(false);
				overDegrees.setEnabled(false);
				editor.putInt("disable_val", 1);
			} else { // OFF.
				dirDegrees.setEnabled(true);
				locDegrees.setEnabled(true);
				overDegrees.setEnabled(true);
				editor.putInt("disable_val", 0);

			}
			editor.commit();
		}

	} // Fin clase interna 'CheckBoxListener'

	/**
	 * Oyente encargado de gestionar las pulsaciones sobre los botones de la
	 * actividad.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 26-08-2013
	 */
	private class SettingsBtnListener implements View.OnClickListener {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Se ejecuta cuando alguno de los elementos que gestiona es pulsado.
		 */
		@Override
		public void onClick(View v) {
			// El valor de los parametros se guarda de forma persistente.
			SharedPreferences prefs = getSharedPreferences("SOAS_prefs",
					Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = prefs.edit();

			// Se evalua que boton de la pantalla fue pulsado y se actua
			// consecuentemente.
			switch (v.getId()) {
			case R.id.save_button: { // GUARDAR.
				// Se comprueba si la validacion esta desactivada.
				if (disableBtn.isChecked()) { // DESHABILITADA.
					AppContext.toast
							.setText("Unable to save. Validation is disabled");
					AppContext.toast.show();
				} else { // HABILITADA.
					// Se recuperan los parametros introducidos por el usuario.
					HashMap<String, String> params = new HashMap<String, String>();
					params.put("direction", dirDegrees.getText().toString());
					params.put("location", locDegrees.getText().toString());
					params.put("overtaking", overDegrees.getText().toString());

					// Se guarda unicamente los parametros que sean correctos.
					boolean paramsOK = true;
					for (Map.Entry<String, String> entry : params.entrySet()) {
						int value = -1;
						if (entry.getValue() != null) {
							if (entry.getValue().matches("\\d+")) {
								value = Integer.parseInt(entry.getValue());
							}
						}
						if ((value < 0) || (value > 360)) {
							paramsOK = false;
						} else {
							editor.putInt(entry.getKey(), value);
						}
					}
					editor.commit();
					updateSettings();

					// Si algun parametro no es correcto se muestra un mensaje
					// al usario.
					if (!paramsOK) {
						AppContext.toast.setDuration(Toast.LENGTH_LONG);
						AppContext.toast
								.setText("Unable to save all params\nDegrees should be between 0 and 360");
						AppContext.toast.setDuration(Toast.LENGTH_SHORT);
					} else {
						AppContext.toast
								.setText("Parameters saved successfully");
					}
					AppContext.toast.show();
				}
				break;
			}
			case R.id.restore_button: { // RESTABLECER.
				editor.putInt("direction", DEFAULT_DIR_DEGREES);
				editor.putInt("location", DEFAULT_LOC_DEGREES);
				editor.putInt("overtaking", DEFAULT_OVER_DEGREES);
				editor.putInt("disable_val", 0);
				editor.commit();
				updateSettings();
				AppContext.toast.setText("Parameters restored successfully");
				AppContext.toast.show();
				break;
			}
			}
		}

	} // Fin clase interna 'SettingsBtnListener'

} // Fin clase 'SettingsActivity'
