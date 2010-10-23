/*
 * Copyright (C) 2010 Herbert von Broeuschmeul
 * Copyright (C) 2010 BluetoothGPS4Droid Project
 * 
 * This file is part of BluetoothGPS4Droid.
 *
 * BluetoothGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * BluetoothGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with BluetoothGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

package org.broeuschmeul.android.gps.bluetooth.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.broeuschmeul.android.gps.nmea.util.NmeaParser;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.location.LocationManager;
import android.location.GpsStatus.NmeaListener;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;

public class BlueetoothGpsManager {

	private class ConnectedGps extends Thread {
		private final InputStream in;

		public ConnectedGps(BluetoothSocket socket) {
			InputStream tmpIn = null;
			try {
				tmpIn = socket.getInputStream();
			} catch (IOException e) {
				Log.e("BT test", "error while getting socket streams", e);
			}	
			in = tmpIn;
		}
	
		public void run() {
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(in,"US-ASCII"));
				String s;
				long now = SystemClock.uptimeMillis();
				long lastRead = now;
				while((enabled) && (now < lastRead+5000 )){
					if (reader.ready()){
						s = reader.readLine();
						Log.e("BT test", "data: "+System.currentTimeMillis()+" "+s + "xxx");
						notifyNmeaSentence(s+"\r\n");
						lastRead = SystemClock.uptimeMillis();
					} else {
						Log.e("BT test", "data: not ready "+System.currentTimeMillis());
						SystemClock.sleep(500);
					}
					now = SystemClock.uptimeMillis();
				}
			} catch (IOException e) {
				Log.e("BT test", "error while getting data", e);
				setMockLocationProviderOutOfService();
			} finally {
				disableIfNeeded();
			}
		}
	}

	private Service callingService;
	private BluetoothSocket gpsSocket;
	private String gpsDeviceAddress;
	private NmeaParser parser = new NmeaParser(10f);
	private boolean enabled = false;
	private ExecutorService notificationPool;
	private ScheduledExecutorService connectionAndReadingPool;
	private List<NmeaListener> nmeaListeners = Collections.synchronizedList(new LinkedList<NmeaListener>()); 
	private LocationManager locationManager;
	private SharedPreferences sharedPreferences;
	private ConnectedGps connectedGps;
	private int disableReason = 0;
	private Notification connectionProblemNotification;
	private Notification serviceStoppedNotification;
	private Context appContext;
	private NotificationManager notificationManager;
	private int maxConnectionRetries;
	private int nbRetriesRemaining;
	private boolean connected = false;

	public BlueetoothGpsManager(Service callingService, String deviceAddress, int maxRetries) {
		this.gpsDeviceAddress = deviceAddress;
		this.callingService = callingService;
		this.maxConnectionRetries = maxRetries;
		this.nbRetriesRemaining = 1+maxRetries;
		this.appContext = callingService.getApplicationContext();
		locationManager = (LocationManager)callingService.getSystemService(Context.LOCATION_SERVICE);
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingService);
		notificationManager = (NotificationManager)callingService.getSystemService(Context.NOTIFICATION_SERVICE);
		parser.setLocationManager(locationManager);	
		
		connectionProblemNotification = new Notification();
		connectionProblemNotification.icon = R.drawable.icon;
		Intent stopIntent = new Intent(BluetoothGpsProviderService.ACTION_STOP_GPS_PROVIDER);
		// PendingIntent stopPendingIntent = PendingIntent.getService(appContext, 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT);
		PendingIntent stopPendingIntent = PendingIntent.getService(appContext, 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT);
		connectionProblemNotification.contentIntent = stopPendingIntent;

		serviceStoppedNotification = new Notification();
		serviceStoppedNotification.icon=R.drawable.icon;
		Intent restartIntent = new Intent(BluetoothGpsProviderService.ACTION_START_GPS_PROVIDER);
		PendingIntent restartPendingIntent = PendingIntent.getService(appContext, 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT);
		serviceStoppedNotification.setLatestEventInfo(appContext, 
				appContext.getString(R.string.service_closed_because_connection_problem_notification_title), 
				appContext.getString(R.string.service_closed_because_connection_problem_notification), 
				restartPendingIntent);
	}

	private void setDisableReason(int reasonId){
		disableReason = reasonId;
	}
	
	public int getDisableReason(){
		return disableReason;
	}
	
	/**
	 * @return true if the bluetooth GPS is enabled
	 */
	public synchronized boolean isEnabled() {
		return enabled;
	}

	public synchronized boolean enable() {
		notificationManager.cancel(R.string.service_closed_because_connection_problem_notification_title);
		if (! enabled){
			final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
	        if (bluetoothAdapter == null) {
	            // Device does not support Bluetooth
	        	Log.e("BT test", "Device does not support Bluetooth");
	        	disable(R.string.msg_bluetooth_unsupported);
	        } else if (!bluetoothAdapter.isEnabled()) {
	        	// Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
	        	// startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
	        	Log.e("BT test", "Bluetooth is not enabled");
	        	disable(R.string.msg_bluetooth_disabled);
	        } else if (Settings.Secure.getInt(callingService.getContentResolver(),Settings.Secure.ALLOW_MOCK_LOCATION, 0)==0){
	        	Log.e("BT test", "Mock location provider OFF");
	        	disable(R.string.msg_mock_location_disabled);
//	        } else if ( (! locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
//	        		 && (sharedPreferences.getBoolean(BluetoothGpsProviderService.PREF_REPLACE_STD_GPS, true))
//	        			) {
//	        	Log.e("BT test", "GPS location provider OFF");
//	        	disable(R.string.msg_gps_provider_disabled);
	        } else {
				final BluetoothDevice gpsDevice = bluetoothAdapter.getRemoteDevice(gpsDeviceAddress);
				if (gpsDevice == null){
					Log.e("BT test", "GPS device not found");       	    	
		        	disable(R.string.msg_bluetooth_gps_unavaible);
				} else {
					Log.e("BT test", "current device: "+gpsDevice.getName() + " -- " + gpsDevice.getAddress());
					try {
						gpsSocket = gpsDevice.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
					} catch (IOException e) {
						Log.e("BT test", "Error during connection", e);
	    				gpsSocket = null;
					}
					if (gpsSocket == null){
						Log.e("BT test", "Error while establishing connection: no socket");
			        	disable(R.string.msg_bluetooth_gps_unavaible);
					} else {
						Runnable connectThread = new Runnable() {							
							@Override
							public void run() {
								try {
									connected = false;
									Log.e("BT test", "current device: "+gpsDevice.getName() + " -- " + gpsDevice.getAddress());
									if ((bluetoothAdapter.isEnabled()) && (nbRetriesRemaining > 0 )){										
										try {
											if (gpsSocket != null){
												Log.e("BT test", "trying to close old socket");
												gpsSocket.close();
											}
										} catch (IOException e) {
											Log.e("BT test", "Error during disconnection", e);
										}
										try {
											gpsSocket = gpsDevice.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
										} catch (IOException e) {
											Log.e("BT test", "Error during connection", e);
						    				gpsSocket = null;
										}
										if (gpsSocket == null){
											Log.e("BT test", "Error while establishing connection: no socket");
								        	disable(R.string.msg_bluetooth_gps_unavaible);
										} else {
											// Cancel discovery because it will slow down the connection
											bluetoothAdapter.cancelDiscovery();
											// we increment the number of connection try
											// Connect the device through the socket. This will block
											// until it succeeds or throws an exception
											gpsSocket.connect();
											// connection obtained so reset the number of connection try
											connected = true;
											nbRetriesRemaining = 1+maxConnectionRetries ;
											notificationManager.cancel(R.string.connection_problem_notification_title);
											connectedGps = new ConnectedGps(gpsSocket);
											connectionAndReadingPool.execute(connectedGps);
										}
									}
								} catch (IOException connectException) {
									// Unable to connect
									Log.e("BT test", "error while connecting to socket", connectException);									
									disable(R.string.msg_bluetooth_gps_unavaible);
								} finally {
									nbRetriesRemaining--;
									if (! connected) {
										disableIfNeeded();
									}
								}
							}
						};
						this.enabled = true;
						notificationPool = Executors.newSingleThreadExecutor();
						connectionAndReadingPool = Executors.newSingleThreadScheduledExecutor();
						connectionAndReadingPool.scheduleWithFixedDelay(connectThread, 5000, 60000, TimeUnit.MILLISECONDS);
					}
				}
			}
		}
		return this.enabled;
	}

	private synchronized void disableIfNeeded(){
		if (enabled){
			if (nbRetriesRemaining > 0){
				// Unable to connect
				Log.e("BT test", "Unable to establish connection");
				connectionProblemNotification.when = System.currentTimeMillis();
				String pbMessage = appContext.getResources().getQuantityString(R.plurals.connection_problem_notification, nbRetriesRemaining, nbRetriesRemaining);
				connectionProblemNotification.setLatestEventInfo(appContext, 
						appContext.getString(R.string.connection_problem_notification_title), 
						pbMessage, 
						connectionProblemNotification.contentIntent);
				connectionProblemNotification.number = 1 + maxConnectionRetries - nbRetriesRemaining;
				notificationManager.notify(R.string.connection_problem_notification_title, connectionProblemNotification);
			} else {
				notificationManager.cancel(R.string.connection_problem_notification_title);
				serviceStoppedNotification.when = System.currentTimeMillis();
				notificationManager.notify(R.string.service_closed_because_connection_problem_notification_title, serviceStoppedNotification);
				disable();
			}
		}
	}
	
	public synchronized void disable(int reasonId) {
		setDisableReason(reasonId);
    	disable();
	}
		
	public synchronized void disable() {
		if (enabled){
			enabled = false;
			if (gpsSocket != null){
				try {
					gpsSocket.close();
				} catch (IOException closeException) {
					Log.e("BT test", "error while closing socket", closeException);
				}
			}
			nmeaListeners.clear();
			disableMockLocationProvider();
			notificationPool.shutdown();
			connectionAndReadingPool.shutdown();
			notificationManager.cancel(R.string.connection_problem_notification_title);
			callingService.stopSelf();
		}
	}

	public void enableMockLocationProvider(String gpsName){
		if (parser != null){
			parser.enableMockLocationProvider(gpsName);
		}
	}

	public void disableMockLocationProvider(){
		if (parser != null){
			parser.disableMockLocationProvider();
		}
	}

	/**
	 * @return the mockGpsEnabled
	 */
	public boolean isMockGpsEnabled() {
		boolean mockGpsEnabled = false;
		if (parser != null){
			mockGpsEnabled = parser.isMockGpsEnabled();
		}
		return mockGpsEnabled;
	}
	/**
	 * @return the mockLocationProvider
	 */
	public String getMockLocationProvider() {
		String  mockLocationProvider = null;
		if (parser != null){
			mockLocationProvider = parser.getMockLocationProvider();
		}
		return mockLocationProvider;
	}

	private void setMockLocationProviderOutOfService(){
		if (parser != null){
			parser.setMockLocationProviderOutOfService();
		}
	}

	public boolean addNmeaListener(NmeaListener listener){
		if (!nmeaListeners.contains(listener)){
			nmeaListeners.add(listener);
		}
		return true;
	}

	public void removeNmeaListener(NmeaListener listener){
		nmeaListeners.remove(listener);
	}

	private void notifyNmeaSentence(final String nmeaSentence){
		if (enabled){
			String sentence = null;
			try {
				sentence = parser.parseNmeaSentence(nmeaSentence);
			} catch (SecurityException e){
				// a priori Mock Location is disabled
				sentence = null;
				disable(R.string.msg_mock_location_disabled);
			}
			final String recognizedSentence = sentence;
			final long timestamp = System.currentTimeMillis();
			if (recognizedSentence != null){
				Log.e("BT test", "NMEA : "+timestamp+" "+recognizedSentence);
				synchronized(nmeaListeners) {
					for(final NmeaListener listener : nmeaListeners){
						notificationPool.execute(new Runnable(){
							@Override
							public void run() {
								listener.onNmeaReceived(timestamp, recognizedSentence);
							}					 
						});
					}
				}
			}
		}
	}	
}
