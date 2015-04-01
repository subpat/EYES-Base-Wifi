package com.tfm.soas.view_controller;

import com.tfm.soas.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.TabHost;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TabHost.TabSpec;

/**
 * Actividad que permite visualizar los logs Cliente/Servidor que contienen los
 * eventos ocurridos durante una sesion SOAS.
 * 
 * @author Javier Herrero Arnanz
 * @version 1.0
 * @since 25-08-2014
 */
public class LogsActivity extends Activity {

	/*--------------------------------------------------------*/
	/* ///////////////////// CONSTANTES ///////////////////// */
	/*--------------------------------------------------------*/
	public static final int CLIENT_LOG = 1;
	public static final int SERVER_LOG = 2;

	/*--------------------------------------------------------*/
	/* ///////////////////// ATRIBUTOS ////////////////////// */
	/*--------------------------------------------------------*/
	private TextView labelC = null; // Etiqueta pestaña Cliente.
	private TextView labelS = null; // Etiqueta pestaña Servidor.
	private int logSelected = CLIENT_LOG; // Pestaña seleccionada.
	private SQLiteDatabase db = null; // Acceso a BD.

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
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Se carga la interfaz de la actividad.
		setContentView(R.layout.activity_logs);

		// Se inicializan las pestañas para cambiar entre logs.
		customizeTabs();
	}

	/**
	 * Permite inicializar el menu de la actividad, el cual permite borrar los
	 * logs.
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Se añade el contenido del menu.
		getMenuInflater().inflate(R.menu.activity_logs, menu);

		return true;
	}

	/**
	 * Permite detectar las pulsaciones sobre los elementos del menu y llevar a
	 * cabo las acciones oportunas.
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Se evalua que elemento del menu fue seleccionado.
		switch (item.getItemId()) {
		case R.id.menu_logs: { // DELETE LOGS.
			// Se eliminan los logs de cliente y servidor.
			db = this.openOrCreateDatabase("sessionLogs", MODE_PRIVATE, null);
			db.execSQL("DROP TABLE IF EXISTS serverLog");
			db.execSQL("DROP TABLE IF EXISTS clientLog");
			db.close();
			showLogs(logSelected);
			return true;
		}
		}
		return false;
	}

	/**
	 * Metodo encargado de personalizar las pestañas que permiten cambiar entre
	 * el log cliente y el log servidor.
	 */
	private void customizeTabs() {
		// Se crea e inicializa el contenedor de la ventana con pestañas.
		TabHost host = (TabHost) findViewById(R.id.logsTabhost);
		host.setup();

		// Se crea y añade la primera pestaña con su etiqueta.
		labelC = new TextView(this);
		labelC.setBackgroundColor(Color.rgb(180, 180, 180));
		labelC.setText((String) getResources().getString(R.string.client_label));
		labelC.setGravity(Gravity.CENTER);
		labelC.setTextSize(20);
		labelC.setTextColor(Color.rgb(0, 0, 0));
		labelC.setTypeface(null, Typeface.BOLD);
		TabSpec spec = host.newTabSpec("TAB1");
		spec.setIndicator(labelC);
		spec.setContent(R.id.tab1);
		host.addTab(spec);

		// Se crea y añade la segunda pestaña con su etiqueta.
		labelS = new TextView(this);
		labelS.setBackgroundColor(Color.TRANSPARENT);
		labelS.setText((String) getResources().getString(R.string.server_label));
		labelS.setGravity(Gravity.CENTER);
		labelS.setTextSize(20);
		labelS.setTextColor(Color.rgb(0, 0, 0));
		labelS.setTypeface(null, Typeface.BOLD);
		spec = host.newTabSpec("TAB2");
		spec.setIndicator(labelS);
		spec.setContent(R.id.tab2);
		host.addTab(spec);

		// Se establece la pestaña a mostrar por defecto y el oyente de
		// pulsaciones sobre las pestañas.
		host.setCurrentTabByTag("TAB1");
		host.setOnTabChangedListener(new TabClickListener());

		// Se muestran el log del cliente.
		showLogs(CLIENT_LOG);
		logSelected = CLIENT_LOG;
	}

	/**
	 * Permite cargar el log indicado.
	 * 
	 * @param type
	 *            1-Log cliente / 2-Log servidor
	 */
	@SuppressLint("RtlHardcoded")
	private void showLogs(int type) {
		// Se recupera la referencia a la tabla donde se mostrara el log.
		TableLayout table = null;
		String tableBD = "";
		if (type == CLIENT_LOG) { // CLIENTE.
			table = (TableLayout) findViewById(R.id.tableClientTab);
			tableBD = "clientLog";
		} else if (type == SERVER_LOG) { // SERVIDOR.
			table = (TableLayout) findViewById(R.id.tableServerTab);
			tableBD = "serverLog";
		}
		table.removeAllViews();

		// Se recupera la informacion del log solicitado de la BD.
		Cursor cursor = null;
		db = this.openOrCreateDatabase("sessionLogs", MODE_PRIVATE, null);
		db.execSQL("CREATE TABLE IF NOT EXISTS "
				+ tableBD
				+ " (id INTEGER PRIMARY KEY autoincrement, time TEXT NOT NULL, info TEXT NOT NULL)");
		cursor = db.rawQuery("SELECT * FROM " + tableBD + " ORDER BY time ASC",
				new String[] {});

		// Se añaden las lineas del log a la tabla.
		if (!cursor.moveToNext()) { // NO HAY INFORMACION.
			// Se añaden 6 lineas vacias.
			for (int i = 0; i < 6; i++) {
				TableRow row = new TableRow(this);
				TextView row_content = new TextView(this);
				row_content.setText("");
				row.addView(row_content);
				table.addView(row);
			}

			// Se indica que no hay informacion.
			TableRow row = new TableRow(this);
			TextView row_content = new TextView(this);
			row_content.setText("The log is empty");
			row_content.setTextColor(Color.rgb(0, 0, 0));
			row_content.setTextSize(18);
			row_content.setGravity(Gravity.CENTER);
			row.addView(row_content);
			table.addView(row);

		} else { // HAY INFORMACION.
			do {
				TableRow row = new TableRow(this);

				// Informacion.
				TextView row_content = new TextView(this);
				row_content.setText(cursor.getString(1) + " - "
						+ cursor.getString(2));
				row_content.setTextColor(Color.rgb(0, 67, 124));
				row_content.setTextSize(17);
				row_content.setGravity(Gravity.LEFT);
				row.addView(row_content);

				// Separador.
				View line = new View(this);
				line.setPadding(0, 5, 0, 5);
				line.setLayoutParams(new LayoutParams(
						LayoutParams.WRAP_CONTENT, 1));
				line.setBackgroundColor(Color.BLACK);

				// Se incorpora a la tabla.
				table.addView(row);
				table.addView(line);
			} while (cursor.moveToNext());

		}

		// Se cierra la BD y el Cursor.
		cursor.close();
		db.close();
	}

	/*--------------------------------------------------------*/
	/* /////////////////// CLASES INTERNAS ////////////////// */
	/*--------------------------------------------------------*/
	/**
	 * Oyente encargado de gestionar las pulsaciones sobre las pestañas de la
	 * pantalla.
	 * 
	 * @author Javier Herrero Arnanz
	 * @version 1.0
	 * @since 26-08-2014
	 */
	private class TabClickListener implements OnTabChangeListener {

		/*---------*/
		/* METODOS */
		/*---------*/
		/**
		 * Se ejecuta cuando alguno de los elementos que gestiona es pulsado.
		 * 
		 * @param tabId
		 *            Pestaña seleccionada
		 */
		@Override
		public void onTabChanged(String tabId) {
			// El fondo de la pestaña seleccionada se colorea.
			if (tabId.compareTo("TAB1") == 0) { // CLIENTE
				labelC.setBackgroundColor(Color.rgb(180, 180, 180));
				labelS.setBackgroundColor(Color.TRANSPARENT);
				showLogs(CLIENT_LOG);
				logSelected = CLIENT_LOG;
			} else if (tabId.compareTo("TAB2") == 0) { // SERVIDOR
				labelC.setBackgroundColor(Color.TRANSPARENT);
				labelS.setBackgroundColor(Color.rgb(180, 180, 180));
				showLogs(SERVER_LOG);
				logSelected = SERVER_LOG;
			}
		}

	} // Fin clase interna 'TabClickListener'

} // Fin clase 'LogsActivity'
