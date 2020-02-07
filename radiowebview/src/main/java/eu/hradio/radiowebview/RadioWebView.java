package eu.hradio.radiowebview;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omri.radio.Radio;
import org.omri.radio.RadioStatus;
import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceDab;
import org.omri.radioservice.RadioServiceDabEdi;
import org.omri.radioservice.RadioServiceIp;
import org.omri.radioservice.RadioServiceIpStream;
import org.omri.radioservice.RadioServiceType;
import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.TextualDabDynamicLabel;
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusItem;
import org.omri.radioservice.metadata.TextualIpIcy;
import org.omri.radioservice.metadata.TextualMetadataListener;
import org.omri.radioservice.metadata.TextualType;
import org.omri.radioservice.metadata.Visual;
import org.omri.radioservice.metadata.VisualDabSlideShow;
import org.omri.radioservice.metadata.VisualMetadataListener;
import org.omri.radioservice.metadata.VisualType;
import org.omri.tuner.ReceptionQuality;
import org.omri.tuner.Tuner;
import org.omri.tuner.TunerListener;
import org.omri.tuner.TunerStatus;

import eu.hradio.core.audiotrackservice.AudiotrackService;
import eu.hradio.timeshiftplayer.SkipItem;
import eu.hradio.timeshiftplayer.TimeshiftListener;
import eu.hradio.timeshiftplayer.TimeshiftPlayer;

import static eu.hradio.radiowebview.BuildConfig.DEBUG;

public class RadioWebView extends WebView implements TunerListener, ServiceConnection {

	private final static String TAG = "RadioWebView";

	private Context mContext;

	public RadioWebView(Context context) {
		this(context, null);
	}

	public RadioWebView(Context context, AttributeSet attrs) {
		this(context, attrs, Resources.getSystem().getIdentifier("webViewStyle", "attr", "android"));
	}

	public RadioWebView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);

		mContext = context;

		if(mContext != null) {
			getSettings().setJavaScriptEnabled(true);

			if(DEBUG) {
				setWebContentsDebuggingEnabled(true);
			} else {
				setWebContentsDebuggingEnabled(false);
			}

			setWebViewClient(new RadioWebViewClient());
			addJavascriptInterface(this, "radioWeb");

			if(DEBUG)Log.d(TAG, "initializing radio in constructor: " + mContext);
			if (Radio.getInstance().getRadioStatus() == RadioStatus.STATUS_RADIO_SUSPENDED) {
				Radio.getInstance().initialize(mContext);

				for(Tuner tuner : Radio.getInstance().getAvailableTuners()) {
					tuner.subscribe(this);
					//TODO multiple tuners handling
					switch (tuner.getTunerStatus()) {
						case TUNER_STATUS_NOT_INITIALIZED: {
							tuner.initializeTuner();
							break;
						}
						case TUNER_STATUS_INITIALIZED: {
							break;
						}
					}
				}
			}
		}
	}

	public void reset() {
		//reseting the webview with default about:blank page
		this.loadUrl("about:blank");
		unbindAudiotrackService();
		this.clearHistory();
		mNotifiedReady = false;
		mTimeshiftJsSrvIdx = -1;
		mWebViewFinished = false;
	}

	private void bindAudiotrackService() {
		if(DEBUG)Log.d(TAG, "Binding AudiotrackService, ServiceBound: " + mAudiotrackServiceBound);
		if(mContext != null) {
			if(!mAudiotrackServiceBound) {
				if (BuildConfig.DEBUG) Log.d(TAG, "Starting AudioTrackService");
				Intent aTrackIntent = new Intent(mContext, eu.hradio.core.audiotrackservice.AudiotrackService.class);
				mContext.startService(aTrackIntent);

				mContext.bindService(aTrackIntent, this, 0);
			}
		}
	}

	private void unbindAudiotrackService() {
		if(DEBUG)Log.d(TAG, "Unbinding AudiotrackService, ServiceBound: " + mAudiotrackServiceBound);
		if(mContext != null) {
			if(mAudiotrackServiceBound) {
				if(mAudiotrackService != null) {
					mAudiotrackServiceBound = false;
					mContext.unbindService(this);
				}
			}
		}
	}

	@Override
	public void loadUrl(String url) {
		if(DEBUG)Log.d(TAG, "loadUrl override: " + url);
		bindAudiotrackService();

		super.loadUrl(url);
	}

	/* Audiosink Service Connection */
	private boolean mAudiotrackServiceBound = false;
	private AudiotrackService.AudioTrackBinder mAudiotrackService = null;
	@Override
	public void onServiceConnected(ComponentName name, IBinder service) {
		if(BuildConfig.DEBUG)Log.d(TAG, "onServiceConnected AudioTrackService");

		mAudiotrackServiceBound = true;
		mAudiotrackService = (AudiotrackService.AudioTrackBinder)service;
	}

	@Override
	public void onServiceDisconnected(ComponentName name) {
		if(BuildConfig.DEBUG)Log.d(TAG, "onServiceDisconnected AudioTrackService");

		mAudiotrackServiceBound = false;
		mAudiotrackService = null;
	}
	/* */

	private void createRadioWebJsObj() {
		if(DEBUG)Log.d(TAG, "Init Creating JS radioWeb object");

		int curOutputVolume = -1;
		if(mAudiotrackServiceBound) {
			if(mAudiotrackService != null) {
				curOutputVolume = mAudiotrackService.getVolume();
			}
		}

		final String radioWebObjString = "javascript:" +
				"if(radioWeb == null) { " +
					"radioWeb = {};" +
				"}" +
				"radioWeb.status = '" + Radio.getInstance().getRadioStatus().toString() + "';" +
				"radioWeb.volume = "+ curOutputVolume +";" +
				"radioWeb.activeServices = [];" +
				"radioWeb.services = [];" +
				"radioWeb.setVolume = function(newVolume) { radioWeb.javaCall(JSON.stringify({volume: newVolume, task: 'setVolume'})); }" + ";" +
				"radioWeb.listeners = {serviceStarted: [], serviceStopped: [], servicesUpdated: []};" +
				"radioWeb.addEventListener = function(type, listener) {" +
								"if(typeof type == 'string') {" +
									"switch(type) {" +
										"case 'serviceStarted':" +
											"this.listeners.serviceStarted.push(listener);" +
											"break;" +
										"case 'serviceStopped':" +
											"this.listeners.serviceStopped.push(listener);" +
											"break;" +
										"case 'servicesUpdated':" +
											"this.listeners.servicesUpdated.push(listener);" +
											"break;" +
									"}" +
								"}" +
							"};" +
				"radioWeb.removeEventListener = function(type, listener) {" +
								"if(typeof type == 'string') {" +
									"switch(type) {" +
										"case 'serviceStarted':" +
											"remListener = this.listeners.serviceStarted.indexOf(listener);" +
											"if(remListener > -1) {" +
												"this.listeners.serviceStarted.splice(remListener, 1);" +
											"}" +
											"break;" +
										"case 'serviceStopped':" +
											"remListener = this.listeners.serviceStopped.indexOf(listener);" +
											"if(remListener > -1) {" +
												"this.listeners.serviceStopped.splice(remListener, 1);" +
											"}" +
											"break;" +
										"case 'servicesUpdated':" +
											"remListener = this.listeners.servicesUpdated.indexOf(listener);" +
											"if(remListener > -1) {" +
												"this.listeners.servicesUpdated.splice(remListener, 1);" +
											"}" +
											"break;" +
									"}" +
								"}" +
							"};"
				;

		this.evaluateJavascript(radioWebObjString, new ValueCallback<String>() {
			@Override
			public void onReceiveValue(String value) {
				if(DEBUG)Log.d(TAG, "Init createRadioWebJsObj callback: " + (value != null ? value : "null"));
			}
		});
	}

	private void fillRadioServicesJs() {
		if(DEBUG)Log.d(TAG, "Init Filling JS RadioServices");

		StringBuilder srvBuilder = new StringBuilder("javascript:radioWeb.services = [");
		for(RadioService srv : Radio.getInstance().getRadioServices()) {

			switch (srv.getRadioServiceType()) {
				case RADIOSERVICE_TYPE_DAB:
				case RADIOSERVICE_TYPE_EDI: {
					srvBuilder.append(createDabJsService(srv));
					break;
				}
				case RADIOSERVICE_TYPE_IP: {
					srvBuilder.append(createIpJsService(srv));
					break;
				}
				default:
					break;
			}

			srvBuilder.append(",");
		}

		srvBuilder.setLength(srvBuilder.length()-1);
		srvBuilder.append("]");

		this.evaluateJavascript(srvBuilder.toString(), new ValueCallback<String>() {
			@Override
			public void onReceiveValue(String value) {
				if(DEBUG)Log.d(TAG, "Init fillRadioServicesJs callback: " + (value != null ? value : "null"));
			}
		});
	}

	private boolean mNotifiedReady = false;
	private void notifyRadioWebReady() {
		if(DEBUG)Log.d(TAG, "Init Notifying JS radioWebReady");

		mNotifiedReady = true;

		final String radioWebReadyJs = "javascript:window.dispatchEvent(new Event('radioWebReady'));";

		this.evaluateJavascript(radioWebReadyJs, new ValueCallback<String>() {
			@Override
			public void onReceiveValue(String value) {
				if(DEBUG)Log.d(TAG, "Init notifyRadioWebReady callback: " + (value != null ? value : "null"));
			}
		});
	}

	private void updateActiveServicesJs() {
		for(Tuner tuner : Radio.getInstance().getAvailableTuners()) {
			RadioService runSrv = tuner.getCurrentRunningRadioService();
			if(runSrv != null) {
				if(runSrv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_DAB || runSrv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_EDI) {
					getJsServiceIdx(runSrv, new ValueCallback<String>() {
						@Override
						public void onReceiveValue(String value) {
							if(value != null) {
								if(!value.equals("null")) {
									int srvIdx = Integer.parseInt(value);
									final String addActiveSrv = "javascript:" +
											"if(radioWeb != null) {" +
												"radioWeb.activeServices.push(radioWeb.services[" + srvIdx + "]);" +
											"} else {" +
												"console.log('radioWeb is null at adding active service');" +
											"}";
								}
							}
						}
					});
				}

			}
		}
	}

	private void addActiveServiceJs(int srvIdx) {
		if(DEBUG)Log.d(TAG, "Adding active service with Idx: " + srvIdx);
		if(srvIdx > -1) {
			final String addJsSrv = "javascript:" +
					"if(!radioWeb.activeServices.includes(radioWeb.services[" + srvIdx + "])) {" +
						"radioWeb.activeServices.push(radioWeb.services[" + srvIdx + "]);" +
					"}";

			evaluateJavascript(addJsSrv, null);
		}
	}

	private void removeActiveServiceJs(int srvIdx) {
		if(DEBUG)Log.d(TAG, "Removing active service with Idx: " + srvIdx);
		if(srvIdx > -1) {
			final String remJsSrv = "javascript:" +
					"remSrvIdx = radioWeb.activeServices.indexOf(radioWeb.services[" + srvIdx + "]);" +
					"if(remSrvIdx > -1) {" +
						"radioWeb.activeServices.splice(remSrvIdx, 1);" +
					"}";

			evaluateJavascript(remJsSrv, null);
		}
	}

	private String creatTimeshiftObject() {
		String timeshiftToken = "";
		long sbtMax = -1;
		boolean isPaused = false;
		long curPos = -1;
		long totalDur = -1;

		StringBuilder skipItemsBuilder = new StringBuilder();
		skipItemsBuilder.append("skipItems: [");
		if(mTimeshiftPlayer != null) {
			if (!mTimeshiftPlayer.getSkipItems().isEmpty()) {
				if (DEBUG) Log.d(TAG, "Filling Skipitems array");
				for (SkipItem item : mTimeshiftPlayer.getSkipItems()) {
					try {
						skipItemsBuilder.append(createSkipitemJson(item).toString());
						skipItemsBuilder.append(",");
					} catch (JSONException jsonExc) {
						if (DEBUG) Log.e(TAG, "Error building Skipitem JSON");
						jsonExc.printStackTrace();
					}
				}
				//remove last comma
				skipItemsBuilder.setLength(skipItemsBuilder.length() - 1);
			}
			//skipItemsBuilder.append("],");

			isPaused = mTimeshiftPlayer.isPaused();
			curPos = mTimeshiftPlayer.getCurrentPosition();
			totalDur = mTimeshiftPlayer.getDuration();

			if (mTimeshiftPlayer.getRadioService().getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_EDI) {
				timeshiftToken = ((RadioServiceDabEdi) mTimeshiftPlayer.getRadioService()).getSbtToken();
				sbtMax = ((RadioServiceDabEdi) mTimeshiftPlayer.getRadioService()).getSbtMax();
			}
		}
		skipItemsBuilder.append("],");

		return "{" +
					"paused:" + isPaused + "," +
					"currentPosition:" + curPos + "," +
					"totalDuration: " + totalDur + "," +
					//SBT
					"timeshiftToken: '" + timeshiftToken + "'," +
					"sbtMax: " + sbtMax + "," +
					"listeners: {tsState: [], skipItemAdded: [], skipItemRemoved: [], progress: [], sbtProgress: [], tsVisual: [], tsTextual: []}," +
					"addTimeshiftListener: function(type ,timeshiftlistener) {" +
						"if(typeof type == 'string') {" +
							"switch(type) {" +
								"case 'state':" +
									"this.listeners.tsState.push(timeshiftlistener);" +
									//"radioWeb.javaCall(JSON.stringify({msg: 'TsState listener added', task: 'logBack'}));" +
									"break;" +
								"case 'skipitemadded':" +
									"this.listeners.skipItemAdded.push(timeshiftlistener);" +
									"curListeners = 'Init Added SkipListener, CurrentSkipListeners: ' + this.listeners.skipItemAdded.length;" +
									"radioWeb.javaCall(JSON.stringify({msg: curListeners, task: 'logBack'}));" +
									"break;" +
								"case 'skipitemremoved':" +
									"this.listeners.skipItemRemoved.push(timeshiftlistener);" +
									//"radioWeb.javaCall(JSON.stringify({msg: 'SkipItemRemoved listener added', task: 'logBack'}));" +
									"break;" +
								"case 'sbtprogress':" +
									"this.listeners.sbtProgress.push(timeshiftlistener);" +
									//"radioWeb.javaCall(JSON.stringify({msg: 'SbtProgress listener added', task: 'logBack'}));" +
									"break;" +
								"case 'progress':" +
									"this.listeners.progress.push(timeshiftlistener);" +
									//"radioWeb.javaCall(JSON.stringify({msg: 'Sbt Progress listener added', task: 'logBack'}));" +
									"break;" +
								"case 'visual':" +
									"this.listeners.tsVisual.push(timeshiftlistener);" +
									//"radioWeb.javaCall(JSON.stringify({msg: 'TS Visual listener added', task: 'logBack'}));" +
									"break;" +
								"case 'textual':" +
									"this.listeners.tsTextual.push(timeshiftlistener);" +
									//"radioWeb.javaCall(JSON.stringify({msg: 'TS Textual listener added', task: 'logBack'}));" +
									"break;" +
							"}" +
						"}" +
					"}," +
					"removeTimeshiftListener: function(type ,timeshiftlistener) {" +
						"if(typeof type == 'string') {" +
							"switch(type) {" +
								"case 'state':" +
									"remListener = this.listeners.tsState.indexOf(timeshiftlistener);" +
									"if(remListener > -1) {" +
										"this.listeners.tsState.splice(remListener, 1);" +
									"};" +
									"break;" +
								"case 'skipitemadded':" +
									"remListener = this.listeners.skipItemAdded.indexOf(timeshiftlistener);" +
									//"radioWeb.javaCall(JSON.stringify({msg: 'SkipItemAdded listener removed', task: 'logBack'}));" +
									"if(remListener > -1) {" +
										"this.listeners.skipItemAdded.splice(remListener, 1);" +
									"};" +
									"break;" +
								"case 'skipitemremoved':" +
									"remListener = this.listeners.skipItemRemoved.indexOf(timeshiftlistener);" +
									"if(remListener > -1) {" +
										"this.listeners.skipItemRemoved.splice(remListener, 1);" +
									"};" +
									"break;" +
								"case 'progress':" +
									"remListener = this.listeners.progress.indexOf(timeshiftlistener);" +
									"if(remListener > -1) {" +
										"this.listeners.progress.splice(remListener, 1);" +
									"};" +
									"break;" +
								"case 'sbtprogress':" +
									"remListener = this.listeners.sbtProgress.indexOf(timeshiftlistener);" +
									"if(remListener > -1) {" +
										"this.listeners.sbtProgress.splice(remListener, 1);" +
									"};" +
									"break;" +
								"case 'visual':" +
									"remListener = this.listeners.tsVisual.indexOf(timeshiftlistener);" +
									"if(remListener > -1) {" +
										"this.listeners.tsVisual.splice(remListener, 1);" +
									"};" +
									"break;" +
								"case 'textual':" +
									"remListener = this.listeners.tsTextual.indexOf(timeshiftlistener);" +
									"if(remListener > -1) {" +
										"this.listeners.tsTextual.splice(remListener, 1);" +
									"};" +
									"break;" +
							"}" +
						"}" +
					"}," +

					skipItemsBuilder.toString() +

					//methods
					"pause: function(unPause) { radioWeb.javaCall(JSON.stringify({pause: unPause, task: 'timeshiftPause'})); }" + "," +
					"skipTo: function(skipToItem) { radioWeb.javaCall(JSON.stringify({skipItem: skipToItem, task: 'skipTo'})); }" + "," +
					"seek: function(seekMs) { radioWeb.javaCall(JSON.stringify({seekpos: seekMs, task: 'timeshiftSeek'})); }" +
					"}";
	}

	private String createDabJsService(RadioService srv) {
		if(srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_DAB || srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_EDI) {
			RadioServiceDab dabSrv = (RadioServiceDab)srv;

			String timeshiftJsObj = "";
			boolean timeshiftCapable = false;

			//check if timeshiftplayer is there and which service is currently timeshifted
			if(mTimeshiftPlayer != null && mTimeshiftPlayer.getRadioService() != null) {
				if(DEBUG)Log.d(TAG, "Creating service with ready timeshiftplayer");
				if(dabSrv.getEnsembleId() == ((RadioServiceDab)mTimeshiftPlayer.getRadioService()).getEnsembleId() &&
						dabSrv.getServiceId() == ((RadioServiceDab)mTimeshiftPlayer.getRadioService()).getServiceId() &&
						dabSrv.getEnsembleEcc() == ((RadioServiceDab)mTimeshiftPlayer.getRadioService()).getEnsembleEcc()
				) {
					if(DEBUG)Log.d(TAG, "Service " + mTimeshiftPlayer.getRadioService().getServiceLabel() + " is currently timeshifted");
					timeshiftCapable = true;

					timeshiftJsObj = "timeshift: " + creatTimeshiftObject() + ",";
				}
			} else {
				if(mTimeshiftPlayer == null) {
					if(DEBUG)Log.d(TAG, "Creating service without timeshiftplayer");
				} else {
					if(DEBUG)Log.d(TAG, "Creating service with timeshiftplayer but null service");
				}

				timeshiftCapable = false;
				timeshiftJsObj = "timeshift: " + creatTimeshiftObject() + ",";
			}
			return "{" +
					"type: '" + dabSrv.getRadioServiceType().toString() + "'," +
					"serviceLabel: '" + dabSrv.getServiceLabel() + "'," +
					"serviceId: " + dabSrv.getServiceId() + "," +
					"ensembleId: " + dabSrv.getEnsembleId() + "," +
					"ensembleEcc: " + dabSrv.getEnsembleEcc() + "," +
					"ensembleLabel: '" + dabSrv.getEnsembleLabel() + "'," +
					"ensembleFrequency: " + dabSrv.getEnsembleFrequency() + "," +
					"isProgramme: " + dabSrv.isProgrammeService() + "," +
					"timeshifted: " + timeshiftCapable + "," +
					timeshiftJsObj +
					"listeners: {sls: [], dls: [], epg: [], state: []}" + "," +
					"start: function() { radioWeb.javaCall(JSON.stringify({service: this, task: 'serviceStart'})); }" + "," +
					"stop: function() { radioWeb.javaCall(JSON.stringify({service: this, task: 'serviceStop'})); }" + "," +
					"addEventListener: function(type, listener) { " +
											"if(typeof type == 'string') {" +
												"switch(type) {" +
													"case 'sls':" +
														"this.listeners.sls.push(listener);" +
														"break;" +
													"case 'dls':" +
														"this.listeners.dls.push(listener);" +
														"break;" +
													"case 'epg':" +
														"this.listeners.epg.push(listener);" +
														"break;" +
													"case 'state':" +
														"this.listeners.state.push(listener);" +
														"break;" +
												"}" +
											"}" +
										" }," +
					"removeEventListener: function(type, listener) { " +
											"if(typeof type == 'string') {" +
												"switch(type) {" +
													"case 'sls':" +
														"remListener = this.listeners.sls.indexOf(listener);" +
														"if(remListener > -1) {" +
															"this.listeners.sls.splice(remListener, 1);" +
														"};" +
														"break;" +
													"case 'dls':" +
														"remListener = this.listeners.dls.indexOf(listener);" +
														"if(remListener > -1) {" +
															"this.listeners.dls.splice(remListener, 1);" +
														"};" +
														"break;" +
													"case 'epg':" +
														"remListener = this.listeners.epg.indexOf(listener);" +
														"if(remListener > -1) {" +
															"this.listeners.epg.splice(remListener, 1);" +
														"};" +
														"break;" +
													"case 'state':" +
														"remListener = this.listeners.state.indexOf(listener);" +
														"if(remListener > -1) {" +
															"this.listeners.state.splice(remListener, 1);" +
														"};" +
														"break;" +
												"}" +
											"}" +
										" }" +
					"}";
		}

		return null;
	}

	private String createIpJsService(RadioService srv) {
		if(srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_IP) {
			RadioServiceIp ipSrv = (RadioServiceIp)srv;

			StringBuilder ipSrvBuilder = new StringBuilder(
			 "{" +
						"type: '" + ipSrv.getRadioServiceType().toString() + "'," +
						"serviceLabel: '" + ipSrv.getServiceLabel().replace("'", "\\'") + "'," +
						"listeners: {sls: [], dls: [], epg: [], state: []}" + "," +
						"start: function() { radioWeb.javaCall(JSON.stringify({service: this, task: 'serviceStart'})); }" + "," +
						"addEventListener: function(type, listener) { " +
							"if(typeof type == 'string') {" +
								"switch(type) {" +
									"case 'sls':" +
										"this.listeners.sls.push(listener);" +
					                    //"radioWeb.javaCall(JSON.stringify({msg: 'SLS listener added', task: 'logBack'}));" +
										"break;" +
									"case 'dls':" +
										"this.listeners.dls.push(listener);" +
					                    //"radioWeb.javaCall(JSON.stringify({msg: 'DLS listener added', task: 'logBack'}));" +
										"break;" +
									"case 'epg':" +
										"this.listeners.epg.push(listener);" +
										"break;" +
									"case 'state':" +
										"this.listeners.state.push(listener);" +
					                    //"radioWeb.javaCall(JSON.stringify({msg: 'State listener added', task: 'logBack'}));" +
										"break;" +
								"}" +
							"}" +
						" }," +
						"removeEventListener: function(type, listener) { " +
							"if(typeof type == 'string') {" +
								"switch(type) {" +
									"case 'sls':" +
										"remListener = this.listeners.sls.indexOf(listener);" +
										"if(remListener > -1) {" +
											"this.listeners.sls.splice(remListener, 1);" +
										"};" +
										"break;" +
									"case 'dls':" +
										"remListener = this.listeners.dls.indexOf(listener);" +
										"if(remListener > -1) {" +
											"this.listeners.dls.splice(remListener, 1);" +
										"};" +
										"break;" +
									"case 'epg':" +
										"remListener = this.listeners.epg.indexOf(listener);" +
										"if(remListener > -1) {" +
											"this.listeners.epg.splice(remListener, 1);" +
										"};" +
										"break;" +
									"case 'state':" +
										"remListener = this.listeners.state.indexOf(listener);" +
										"if(remListener > -1) {" +
											"this.listeners.state.splice(remListener, 1);" +
										"};" +
										"break;" +
								"}" +
							"}" +
						" },"
			);

			ipSrvBuilder.append("id: " + ipSrv.hashCode() + ",");
			ipSrvBuilder.append("streams: [");
			for(RadioServiceIpStream ipStream : ipSrv.getIpStreams()) {
				ipSrvBuilder.append("{");
					ipSrvBuilder.append("url: '" + ipStream.getUrl() + "',");
					ipSrvBuilder.append("bitrate: " + ipStream.getBitrate() + ",");
					ipSrvBuilder.append("mimeType: '" + ipStream.getMimeType().getMimeTypeString() + "',");
					ipSrvBuilder.append("cost: " + ipStream.getCost() + ",");
					ipSrvBuilder.append("offset: " + ipStream.getOffset());
				ipSrvBuilder.append("},");
			}

			//remove last char
			ipSrvBuilder.setLength(ipSrvBuilder.length()-1);
			ipSrvBuilder.append("]");

			ipSrvBuilder.append("}"); //END

			return ipSrvBuilder.toString();
		}

		return null;
	}

	private static final String JSON_CALL_TASK                          = "task";
	private static final String JSON_SERVICE_TASK_START                 = "serviceStart";
	private static final String JSON_SERVICE_TASK_STOP                  = "serviceStop";
	private static final String JSON_SERVICE_TASK_SETVOLUME             = "setVolume";
	private static final String JSON_SERVICE                            = "service";
	private static final String JSON_SERVICE_TYPE                       = "type";
	private static final String JSON_SERVICE_DAB_SERVICEID              = "serviceId";
	private static final String JSON_SERVICE_DAB_ENSEMBLEID             = "ensembleId";
	private static final String JSON_SERVICE_DAB_ENSEMBLE_ECC           = "ensembleEcc";

	//Timeshift
	private static final String JSON_SERVICE_TASK_TIMESHIFT_PAUSE       = "timeshiftPause";
	private static final String JSON_SERVICE_TASK_TIMESHIFT_SEEK        = "timeshiftSeek";
	private static final String JSON_SERVICE_TASK_TIMESHIFT_SKIPTO      = "skipTo";

	private static final String JSON_GENERAL_LOGBACK                    = "logBack";

	@JavascriptInterface
	public void javaCall(String call) {
		Log.d(TAG, "JavaCall from RadioWeb: " + call);

		try {
			JSONObject callObj = new JSONObject(call);
			if(callObj.has(JSON_CALL_TASK)) {
				if(callObj.getString(JSON_CALL_TASK).equals(JSON_SERVICE_TASK_START)) {
					if(DEBUG)Log.d(TAG, "Starting Service from JS call!");
					RadioService srv = getServiceFromJson(callObj.getJSONObject(JSON_SERVICE));
					if(srv != null) {
						//TODO dont play Shoutcast services when timeshiftplayer active
						if(srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_IP) {
							if(DEBUG)Log.d(TAG, "Not Tuning IP_SHOUTCAST service: " + srv.getServiceLabel());
							return;
						}

						Radio.getInstance().startRadioService(srv);
					}
				}
				if(callObj.getString(JSON_CALL_TASK).equals(JSON_SERVICE_TASK_STOP)) {
					if(DEBUG)Log.d(TAG, "Stopping Service from JS call!");
					RadioService srv = getServiceFromJson(callObj.getJSONObject(JSON_SERVICE));
					if(srv != null) {
						Radio.getInstance().stopRadioService(srv);
					}
				}
				if(callObj.getString(JSON_CALL_TASK).equals(JSON_SERVICE_TASK_SETVOLUME)) {
					if(DEBUG)Log.d(TAG, "Setting volume from JS call!");
					int newVolume = callObj.getInt("volume");

					if(mAudiotrackServiceBound) {
						if(mAudiotrackService != null) {
							mAudiotrackService.setVolume(newVolume);

							final String setRwebVol = "javascript:" +
									"radioWeb.volume = " + Math.min(Math.max(newVolume, 0), 100) + ";";

							executeOnMainThread(new Runnable() {
								@Override
								public void run() {
									evaluateJavascript(setRwebVol, null);
								}
							});
						}
					}
				}
				if(callObj.getString(JSON_CALL_TASK).equals(JSON_SERVICE_TASK_TIMESHIFT_PAUSE)) {
					if(DEBUG)Log.d(TAG, "pausing timeshiftplayer from JS call!");

					boolean pause = callObj.getBoolean("pause");
					if(mTimeshiftPlayer != null) {
						mTimeshiftPlayer.pause(pause);
					}
				}
				if(callObj.getString(JSON_CALL_TASK).equals(JSON_SERVICE_TASK_TIMESHIFT_SEEK)) {
					if(DEBUG)Log.d(TAG, "seeking timeshiftplayer from JS call!");

					long seekMs = callObj.getLong("seekpos");
					if(mTimeshiftPlayer != null) {
						if(mTimeshiftPlayer.getDuration() >= seekMs) {
							mTimeshiftPlayer.seek(seekMs);
						} else {
							if(DEBUG)Log.d(TAG, "Wanted seekPos out of range, wanted: " + seekMs + ", duration: " + mTimeshiftPlayer.getDuration());
						}
					}
				}
				if(callObj.getString(JSON_CALL_TASK).equals(JSON_SERVICE_TASK_TIMESHIFT_SKIPTO)) {
					if(DEBUG)Log.d(TAG, "skipTo timeshiftplayer from JS call!");

					if(mTimeshiftPlayer != null) {
						JSONObject skipObj = callObj.getJSONObject("skipItem");
						if(skipObj != null) {
							long jsonSkipPos = skipObj.optLong("relativeSkipPoint");
							for (SkipItem srchItem : mTimeshiftPlayer.getSkipItems()) {
								if (srchItem.getRelativeTimepoint() == jsonSkipPos) {
									mTimeshiftPlayer.skipTo(srchItem);
								}
							}
						}
					}
				}
				if(callObj.getString(JSON_CALL_TASK).equals(JSON_GENERAL_LOGBACK)) {
					if(DEBUG)Log.d(TAG, "LogBack: " + callObj.getString("msg"));
				}
			}
		} catch(JSONException jsonExc) {
			jsonExc.printStackTrace();
		}
	}

	private RadioService getServiceFromJson(JSONObject srvJson) {
		try {
			if(srvJson.getString(JSON_SERVICE_TYPE).equals(RadioServiceType.RADIOSERVICE_TYPE_DAB.toString()) || srvJson.getString(JSON_SERVICE_TYPE).equals(RadioServiceType.RADIOSERVICE_TYPE_EDI.toString())) {
				int serviceId = srvJson.getInt(JSON_SERVICE_DAB_SERVICEID);
				int ensembleId = srvJson.getInt(JSON_SERVICE_DAB_ENSEMBLEID);
				int ensembleEcc = srvJson.getInt(JSON_SERVICE_DAB_ENSEMBLE_ECC);

				for(RadioService srv : Radio.getInstance().getRadioServices()) {
					if(srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_DAB || srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_EDI) {
						RadioServiceDab dabSrv = (RadioServiceDab)srv;
						if(dabSrv.getServiceId() == serviceId && dabSrv.getEnsembleId() == ensembleId && dabSrv.getEnsembleEcc() == ensembleEcc) {
							if(DEBUG)Log.d(TAG, "Found wanted service: " + dabSrv.getServiceLabel());

							return dabSrv;
						}
					}
				}
			}
			if(srvJson.getString(JSON_SERVICE_TYPE).equals(RadioServiceType.RADIOSERVICE_TYPE_IP.toString())) {
				int idHash = srvJson.getInt("id");
				for(RadioService srv : Radio.getInstance().getRadioServices()) {
					if(srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_IP) {
						if(srv.hashCode() == idHash) {
							if(DEBUG)Log.d(TAG, "Found IPService: " + srv.hashCode() + " : " + idHash + " : " + srv.getServiceLabel());

							return srv;
						}
					}
				}
			}
		} catch(JSONException exc) {
			if(DEBUG)exc.printStackTrace();
		}

		return null;
	}

	private void getJsServiceIdx(RadioService srv, final ValueCallback<String> valCb) {
		if(srv != null && valCb != null) {
			String getJsSrvIdx = "";
			if (srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_DAB) {
				RadioServiceDab dabSrv = (RadioServiceDab) srv;
				getJsSrvIdx = "javascript:" +
						"(function() {" +
						"for(var i = 0; i < radioWeb.services.length; i++) {" +
						"if(radioWeb.services[i].type == '" + RadioServiceType.RADIOSERVICE_TYPE_DAB.toString() + "') {" +
						"if(radioWeb.services[i].serviceId == " + dabSrv.getServiceId() + " && " +
						"radioWeb.services[i].ensembleId == " + dabSrv.getEnsembleId() + " && " +
						"radioWeb.services[i].ensembleEcc == " + dabSrv.getEnsembleEcc() +
						") {" +
						"return i;" +
						"}" +
						"}" +
						"}" +
						"})()";
			}
			if (srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_EDI) {
				RadioServiceDab dabSrv = (RadioServiceDab) srv;
				getJsSrvIdx = "javascript:" +
						"(function() {" +
							"if(radioWeb != null && radioWeb.services != null) {" +
								"for(var i = 0; i < radioWeb.services.length; i++) {" +
									"if(radioWeb.services[i].type == '" + RadioServiceType.RADIOSERVICE_TYPE_EDI.toString() + "') {" +
										"if(radioWeb.services[i].serviceId == " + dabSrv.getServiceId() + " && " +
											"radioWeb.services[i].ensembleId == " + dabSrv.getEnsembleId() + " && " +
											"radioWeb.services[i].ensembleEcc == " + dabSrv.getEnsembleEcc() +
										") {" +
											"return i;" +
										"}" +
									"}" +
								"}" +
							"} else { console.log('radioWeb object is null'); return null; }" +
						"})()";
			}
			if (srv.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_IP) {
				getJsSrvIdx = "javascript:" +
						"(function() {" +
						"for(var i = 0; i < radioWeb.services.length; i++) {" +
						"if(radioWeb.services[i].type == '" + RadioServiceType.RADIOSERVICE_TYPE_IP.toString() + "') {" +
						"if(radioWeb.services[i].id == " + srv.hashCode() + ") {" +
						"return i;" +
						"}" +
						"}" +
						"}" +
						"})()";
			}

			//this.evaluateJavascript(getJsSrvIdx, valCb);

			final String evalString = getJsSrvIdx;
			executeOnMainThread(new Runnable() {
				@Override
				public void run() {
					evaluateJavascript(evalString, valCb);
				}
			});
		}
	}

	//Executes the given Runnable on the main (UI) thread
	private void executeOnMainThread(Runnable runnable) {
		if(runnable != null) {
			new Handler(mContext.getMainLooper()).post(runnable);
		}
	}

	/*  */
	private boolean mWebViewFinished = false;
	private class RadioWebViewClient extends WebViewClient {

		private WebView mwebView = null;
		private String mUrl = null;

		RadioWebViewClient() {
			if(DEBUG)Log.d(TAG, "Creating new RadioWebViewClient");
		}

		@SuppressWarnings("deprecation")
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			if(DEBUG)Log.d(TAG, "shouldOverrideUrlLoading old");
			if(mUrl != null) {
				if(url.equals(mUrl)) {
					//reloading same page
					return false;
				} else if(mUrl.equals("about:blank")) {
					//load page after reset
					loadUrl(url);
					return false;
				}
			}

			//TODO any other link inside the view will be opened in an external browser App
			Intent extIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			mContext.startActivity(extIntent);
			return true;
		}

		@TargetApi(Build.VERSION_CODES.N)
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			if(DEBUG)Log.d(TAG, "shouldOverrideUrlLoading new: " + (mUrl != null ? mUrl : " mUrl is null"));
			if(request != null && request.getUrl() != null ) {
				if (request.getUrl().toString().equals(mUrl)) {
					//reloading same page
					return false;
				} else if(mUrl.equals("about:blank")) {
					//load page after reset
					loadUrl(request.getUrl().toString());
					return false;
				}

				Intent extIntent = new Intent(Intent.ACTION_VIEW, request.getUrl());
				mContext.startActivity(extIntent);
			}
			return true;

		}

		@Override
		public void onPageStarted(WebView view, String url, Bitmap favicon) {
			if(DEBUG)Log.d(TAG, "onPageStarted: " + url);

			mNotifiedReady = false;
			mWebViewFinished = false;
			mTimeshiftJsSrvIdx = -1;

			if(mwebView != null) {
				if(DEBUG)Log.d(TAG, "Removing old interface");
				mwebView.removeJavascriptInterface("radioWeb");
			}

			mUrl = url;
			mwebView = view;

			//Inject the interface as the main 'radioWeb' object into the loading page
			if(DEBUG)Log.d(TAG, "Injecting JS-interface");
			view.addJavascriptInterface((RadioWebView)view, "radioWeb");
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			super.onPageFinished(view, url);
			if(DEBUG)Log.d(TAG, "onPageFinished: " + url);

			if(!url.equals("about:blank")) {
				mWebViewFinished = true;

				boolean allReady = true;
				for(Tuner availTuner : Radio.getInstance().getAvailableTuners()) {
					if(availTuner.getTunerStatus() != TunerStatus.TUNER_STATUS_INITIALIZED) {
						allReady = false;
						break;
					}
				}
				if(allReady && !mNotifiedReady) {
					if(DEBUG)Log.d(TAG, "Initializing RadioWeb onPageFinished");

					((RadioWebView) view).createRadioWebJsObj();
					((RadioWebView) view).fillRadioServicesJs();
					((RadioWebView) view).updateActiveServicesJs();
					((RadioWebView) view).notifyRadioWebReady();
				}
			} else {
				if(DEBUG)Log.d(TAG, "onPageFinished not loading after reset to: " + url);
			}
		}

		@Override
		public void onLoadResource(WebView view, String url) {
			super.onLoadResource(view, url);
			if(DEBUG)Log.d(TAG, "onLoadResource: " + url);
		}
	}

	/* TunerListener */
	@Override
	public void tunerStatusChanged(Tuner tuner, TunerStatus tunerStatus) {
		if(DEBUG)Log.d(TAG, "Tuner " + tuner.getTunerType() + " status changed to: " + tunerStatus.toString());

		boolean allReady = true;
		for(Tuner availTuner : Radio.getInstance().getAvailableTuners()) {
			if(availTuner.getTunerStatus() != TunerStatus.TUNER_STATUS_INITIALIZED) {
				if(DEBUG)Log.d(TAG, "Tuner is not ready from " + Radio.getInstance().getAvailableTuners().size());
				allReady = false;
				break;
			}
		}
		if(allReady && mWebViewFinished && !mNotifiedReady) {
			if(DEBUG)Log.d(TAG, "Initializing RadioWeb onTunerStatusChanged");
			executeOnMainThread(new Runnable() {
				@Override
				public void run() {
					createRadioWebJsObj();
					fillRadioServicesJs();
					updateActiveServicesJs();
					notifyRadioWebReady();
				}
			});
		}
	}

	@Override
	public void tunerScanStarted(Tuner tuner) {

	}

	@Override
	public void tunerScanProgress(Tuner tuner, int i) {

	}

	@Override
	public void tunerScanFinished(Tuner tuner) {
		if(mNotifiedReady) {
			executeOnMainThread(new Runnable() {
				@Override
				public void run() {
					fillRadioServicesJs();
				}
			});

			final String notify = "javascript:" +
					"if(radioWeb != null) {" +
					"radioWeb.listeners.servicesUpdated.forEach(function(cb) { cb( radioWeb.services ); });" +
					"}";

			executeOnMainThread(new Runnable() {
				@Override
				public void run() {
					evaluateJavascript(notify, null);
				}
			});
		}
	}

	@Override
	public void tunerScanServiceFound(Tuner tuner, RadioService radioService) {

	}

	RadioMetadataHandler mMetadataHandler = null;
	@Override
	public void radioServiceStarted(Tuner tuner, final RadioService radioService) {
		if(DEBUG)Log.d(TAG, "radioServiceStarted: " + radioService.getServiceLabel() + " : " + radioService.getRadioServiceType().toString());

		if(!mWebViewFinished) {
			return;
		}

		executeOnMainThread(new Runnable() {
			@Override
			public void run() {
				getJsServiceIdx(radioService, new ValueCallback<String>() {
					@Override
					public void onReceiveValue(String value) {
						if(value != null) {
							if(!value.equals("null")) {
								try {
									int startedSrvIdx = Integer.parseInt(value);
									radioService.subscribe(mMetadataHandler = new RadioMetadataHandler(startedSrvIdx));
									addActiveServiceJs(startedSrvIdx);

									final String notifySrvStartJs = "javascript:" +
											//"radioWeb.javaCall(JSON.stringify({msg: 'ServiceStarted call', task: 'logBack'}));" +
											"radioWeb.listeners.serviceStarted.forEach(function(cb) { cb( radioWeb.services[" + startedSrvIdx + "] ); });";

									evaluateJavascript(notifySrvStartJs, null);

								} catch(NumberFormatException numExc) {
									if(DEBUG)numExc.printStackTrace();
								}
							} else {
								if(DEBUG)Log.d(TAG, "ValueCallback for serviceStarted is: " + value);
							}
						} else {
							if(DEBUG)Log.d(TAG, "ValueCallback for serviceStarted is null");
						}
					}
				});
			}
		});
	}

	@Override
	public void radioServiceStopped(Tuner tuner, final RadioService radioService) {
		if(DEBUG)Log.d(TAG, "radioServiceStopped: " + radioService.getServiceLabel() + " : " + radioService.getRadioServiceType().toString());

		if(radioService != null) {
			if(DEBUG)Log.d(TAG, "Unregistering matadataHandler");
			radioService.unsubscribe(mMetadataHandler);
		}

		if(!mWebViewFinished) {
			return;
		}

		executeOnMainThread(new Runnable() {
			@Override
			public void run() {
				getJsServiceIdx(radioService, new ValueCallback<String>() {
					@Override
					public void onReceiveValue(String value) {
						if(value != null) {
							if(!value.equals("null")) {
								try {
									int stoppedSrvIdx = Integer.parseInt(value);

									removeActiveServiceJs(stoppedSrvIdx);

									final String callStoppedListenersJs = "javascript:" +
											"radioWeb.listeners.serviceStopped.forEach(function(cb) { cb( radioWeb.services[" + stoppedSrvIdx + "] ); });" +
											"radioWeb.activeServices.splice(radioWeb.activeServices.indexOf(radioWeb.services[" + stoppedSrvIdx + "]), 1);";

									evaluateJavascript(callStoppedListenersJs, null);
								} catch(NumberFormatException numExc) {
									if(DEBUG)numExc.printStackTrace();
								}
							} else {
								if(DEBUG)Log.d(TAG, "ValueCallback for serviceStopped is: " + value);
							}
						} else {
							if(DEBUG)Log.d(TAG, "ValueCallback for serviceStopped is: null");
						}
					}
				});
			}
		});
	}

	@Override
	public void tunerReceptionStatistics(Tuner tuner, boolean b, ReceptionQuality receptionQuality) {

	}

	@Override
	public void tunerRawData(Tuner tuner, byte[] bytes) {

	}

	private class RadioMetadataHandler implements VisualMetadataListener, TextualMetadataListener {

		private final int mJsSrvIdx;

		RadioMetadataHandler(int jsSrvIdx) {
			mJsSrvIdx = jsSrvIdx;
		}

		@Override
		public void newTextualMetadata(Textual textual) {
			if(DEBUG)Log.d(TAG, "New TextualMetadata for JsSrvIdx: " + mJsSrvIdx + ", WebViewFinished: " + mWebViewFinished + ", RadioWebNotified: " + mNotifiedReady);
			if(mWebViewFinished && mNotifiedReady) {
				try {
					final String callDlsCb = "javascript:" +
							"radioWeb.services[" + mJsSrvIdx + "].listeners.dls.forEach(function(cb) { cb(" + createTextualJson(textual) + "); });";

					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callDlsCb, null);
						}
					});
				} catch (JSONException jsonExc) {
					if (DEBUG) jsonExc.printStackTrace();
				}
			}
		}

		@Override
		public void newVisualMetadata(Visual visual) {
			if(DEBUG)Log.d(TAG, "New VisualMetadata for JsSrvIdx: " + mJsSrvIdx + ", WebViewFinished: " + mWebViewFinished + ", RadioWebNotified: " + mNotifiedReady);
			if(mWebViewFinished && mNotifiedReady) {
				try {
					final String callSlsCb = "javascript:" +
							"radioWeb.services[" + mJsSrvIdx + "].listeners.sls.forEach(function(cb) { cb(" + createVisualJson(visual) + "); });";

					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callSlsCb, null);
						}
					});
				} catch (JSONException jsonExc) {
					if (DEBUG) jsonExc.printStackTrace();
				}
			}
		}
	}

	private JSONObject createVisualJson(Visual visual) throws JSONException {
		JSONObject slsObject = null;

		if(visual != null) {
			slsObject = new JSONObject();
			slsObject.put("visualType", visual.getVisualType().toString());

			if (visual.getVisualType() == VisualType.METADATA_VISUAL_TYPE_DAB_SLS) {
				VisualDabSlideShow sls = (VisualDabSlideShow) visual;
				slsObject.put("contentName", sls.getContentName());
				slsObject.put("slideId", sls.getSlideId());
				//TODO parsing of TriggerTime. Only TriggerTime '0' == 'NOW'
				slsObject.put("triggerTime", "NOW");
				switch (sls.getVisualMimeType()) {
					case METADATA_VISUAL_MIMETYPE_PNG: {
						slsObject.put("mimeType", "image/png");
						break;
					}
					case METADATA_VISUAL_MIMETYPE_GIF: {
						slsObject.put("mimeType", "image/gif");
						break;
					}
					case METADATA_VISUAL_MIMETYPE_ANIMATED_GIF: {
						slsObject.put("mimeType", "image/gif");
						break;
					}
					case METADATA_VISUAL_MIMETYPE_JPEG: {
						slsObject.put("mimeType", "image/jpeg");
						break;
					}
					//not supported in DAB
					case METADATA_VISUAL_MIMETYPE_BMP: {
						slsObject.put("mimeType", "image/bmp");
						break;
					}
					//not supported in DAB
					case METADATA_VISUAL_MIMETYPE_SVG: {
						slsObject.put("mimeType", "image/svg+xml");
						break;
					}
					//not supported in DAB
					case METADATA_VISUAL_MIMETYPE_TIFF: {
						slsObject.put("mimeType", "image/tiff");
						break;
					}
					//not supported in DAB
					case METADATA_VISUAL_MIMETYPE_WEBP: {
						slsObject.put("mimeType", "image/webp");
						break;
					}
					default: {
						slsObject.put("mimeType", "image/unknown");
						break;
					}
				}
				slsObject.put("isCategorized", sls.isCategorized());
				slsObject.put("categoryId", sls.getCategoryId());
				slsObject.put("categoryName", (sls.getCategoryText() != null) ? sls.getCategoryText() : "");
				slsObject.put("clickthroughUrl", (sls.getClickThroughUrl() != null) ? sls.getClickThroughUrl().toString() : "");
				slsObject.put("alternativeLocationUrl", (sls.getAlternativeLocationURL() != null) ? sls.getAlternativeLocationURL().toString() : "");
				//TODO expiryTime parsing
				slsObject.put("expiryTime", 0);
				slsObject.put("visualData", Base64.encodeToString(sls.getVisualData(), Base64.NO_WRAP));
			}
		}

		return slsObject;
	}

	private JSONObject createSkipitemJson(SkipItem skipItem) throws JSONException {
		JSONObject skipItemObj = null;

		if(skipItem != null) {
			skipItemObj = new JSONObject();

			skipItemObj.put("relativeSkipPoint", skipItem.getRelativeTimepoint());

			if(mTimeshiftPlayer != null) {
				if(mTimeshiftPlayer.getRadioService().getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_EDI) {
					skipItemObj.put("sbtRealTime", skipItem.getSbtRealTime());
					skipItemObj.put("toggleId", skipItem.getSkipPoint());
				}
			}

			JSONObject textualJson = createTextualJson(skipItem.getSkipTextual());
			skipItemObj.put("skipTextual", textualJson);
			JSONObject visualJson = createVisualJson(skipItem.getSkipVisual());
			skipItemObj.put("skipVisual", visualJson);
		}

		return skipItemObj;
	}

	private JSONObject createTextualJson(Textual textual) throws JSONException {
		JSONObject dlsObject = null;

		if(textual != null) {
			dlsObject = new JSONObject();
			dlsObject.put("textualType", textual.getType().toString());
			if (textual.getType() == TextualType.METADATA_TEXTUAL_TYPE_DAB_DLS) {
				TextualDabDynamicLabel dls = (TextualDabDynamicLabel) textual;

				dlsObject.put("dls", textual.getText().replace("'", "\\'"));
				dlsObject.put("itemRunning", dls.itemRunning());
				dlsObject.put("itemToggled", dls.itemToggled());
				JSONArray dlPlusItemsArr = new JSONArray();
				if (dls.hasTags()) {
					for (TextualDabDynamicLabelPlusItem item : dls.getDlPlusItems()) {
						JSONObject dlPlusItem = new JSONObject();
						dlPlusItem.put("contentType", item.getDynamicLabelPlusContentType().toString());
						dlPlusItem.put("contentCategory", item.getDlPlusContentCategory());
						dlPlusItem.put("contentTypeDescription", item.getDlPlusContentTypeDescription());
						dlPlusItem.put("dlPlusText", item.getDlPlusContentText().replace("'", "\\'"));

						dlPlusItemsArr.put(dlPlusItem);
					}
				}
				dlsObject.put("dlPlusItems", dlPlusItemsArr);
			}
			if (textual.getType() == TextualType.METADATA_TEXTUAL_TYPE_ICY_TEXT) {
				TextualIpIcy icy = (TextualIpIcy) textual;
				dlsObject.put("dls", icy.getText().replace("'", "\\'"));
				dlsObject.put("itemRunning", false);
				dlsObject.put("itemToggled", false);
				JSONArray dlPlusItemsArr = new JSONArray();
				dlsObject.put("dlPlusItems", dlPlusItemsArr);
			}
		}

		return dlsObject;
	}

	private TimeshiftPlayer mTimeshiftPlayer = null;
	private int mTimeshiftJsSrvIdx = -1;
	public void setTimeshiftPlayer(final TimeshiftPlayer tsPlayer) {
		if(DEBUG)Log.d(TAG, "TS Setting new TimeshiftPlayer");
		//new timeshiftplayer set, check old player for set service and disable timeshift object on it
		if(mTimeshiftPlayer != null) {
			if(mTimeshiftPlayer.getRadioService() != null) {
				setJsSrvTimeshiftCapable(mTimeshiftPlayer.getRadioService(), false);
			}
		}

		if(tsPlayer != null) {
			mTimeshiftPlayer = tsPlayer;

			setJsSrvTimeshiftCapable(mTimeshiftPlayer.getRadioService(), true);
			mTimeshiftPlayer.addListener(mTimeshiftListener);
		}
	}
	private boolean mPrematureStart = false;
	private void setJsSrvTimeshiftCapable(final RadioService service, final boolean timeshiftCapable) {
		if(DEBUG)Log.d(TAG, "Setting Service " + service.getServiceLabel() + " timeshiftCapable to: " + timeshiftCapable);

		executeOnMainThread(new Runnable() {
			@Override
			public void run() {
				getJsServiceIdx(service, new ValueCallback<String>() {
					@Override
					public void onReceiveValue(String value) {
						if(value != null) {
							if(!value.equals("null")) {
								try {
									int startedSrvIdx = Integer.parseInt(value);

									if(DEBUG)Log.d(TAG, "TS Setting timeshifted to " + timeshiftCapable + " for: " + service.getServiceLabel() + " with JsIdx: " + startedSrvIdx);

									String setTsCapJs = "javascript:" +
											"radioWeb.services[" + startedSrvIdx + "].timeshifted = " + timeshiftCapable + ";";
											if(!timeshiftCapable) {
												//setTsCapJs  += "radioWeb.services[" + startedSrvIdx + "].timeshift = null;";
												//setTsCapJs  += "delete radioWeb.services[" + startedSrvIdx + "].timeshift;";
												setTsCapJs  += "radioWeb.services[" + startedSrvIdx + "].timeshift.skipItems = [];" +
												"radioWeb.services[" + startedSrvIdx + "].timeshift.timeshiftToken = '';" +
												"radioWeb.services[" + startedSrvIdx + "].timeshift.sbtMax = -1;" +
												"radioWeb.services[" + startedSrvIdx + "].timeshift.totalDuration = 0;" +
												"radioWeb.services[" + startedSrvIdx + "].timeshift.currentPosition = 0;";
												removeActiveServiceJs(startedSrvIdx);
											} else {
												//setTsCapJs  += "radioWeb.services[" + startedSrvIdx + "].timeshift = " + creatTimeshiftObject() + ";";
												addActiveServiceJs(startedSrvIdx);
											}

									evaluateJavascript(setTsCapJs, null);

									if(timeshiftCapable) {
										mTimeshiftJsSrvIdx = startedSrvIdx;
										if(DEBUG)Log.d(TAG, "TS mTimeshiftJsSrvIdx " + mTimeshiftJsSrvIdx + ", premature: " + mPrematureStart);
										if(mPrematureStart) {
											if(DEBUG)Log.d(TAG, "TS calling started again");
											mTimeshiftListener.started();
											mPrematureStart = false;
										}
									} else {
										mTimeshiftJsSrvIdx = -1;
									}

								} catch(NumberFormatException numExc) {
									if(DEBUG)numExc.printStackTrace();
								}
							} else {
								if(DEBUG)Log.d(TAG, "TS ValueCallback for setJsSrvTimeshiftCapable is: " + value);
							}
						} else {
							if(DEBUG)Log.d(TAG, "TS ValueCallback for setJsSrvTimeshiftCapable is null");
						}
					}
				});
			}
		});
	}

	private TimeshiftListener mTimeshiftListener = new TimeshiftListener() {

		@Override
		public void progress(long cur, long total) {
			//TODO not very smart. do this somewhere else....
			if(mWebViewFinished && mNotifiedReady) {
				if (mTimeshiftJsSrvIdx <= -1) {
					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							getJsServiceIdx(mTimeshiftPlayer.getRadioService(), new ValueCallback<String>() {
								@Override
								public void onReceiveValue(String value) {
									if (value != null) {
										if (!value.equals("null")) {
											try {
												mTimeshiftJsSrvIdx = Integer.parseInt(value);
												addActiveServiceJs(mTimeshiftJsSrvIdx);
												//registering the live metadatahandler to the running service
												mTimeshiftPlayer.getRadioService().subscribe(mMetadataHandler = new RadioMetadataHandler(mTimeshiftJsSrvIdx));

												if(DEBUG)Log.d(TAG, "Init getting TsSrvIdx: " + mTimeshiftJsSrvIdx + " and setting active service");
											} catch (NumberFormatException numExc) {
												if(DEBUG)numExc.printStackTrace();
											}
										} else {
											if(DEBUG)Log.d(TAG, "ValueCallback for progress is: " + value);
										}
									} else {
										if(DEBUG)Log.d(TAG, "ValueCallback for progress is null");
									}
								}
							});
						}
					});
				} else {
					final String progressJson = "{ currentPosition: " + cur + ",  totalDuration: " + total + " }";
					if(DEBUG)Log.d(TAG, "ProgressJson: " + progressJson);
					final String callTsProgressCb = "javascript:" +
							"if(radioWeb != null) {" +
								"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.progress.forEach(function(cb) { cb(" + progressJson + "); });" +
								"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.currentPosition = " + cur + ";" +
								"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.totalDuration = " + total + ";" +
							"}";

					//must be executed in the main thread
					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callTsProgressCb, null);
						}
					});
				}
			}
		}

		@Override
		public void sbtRealTime(long realTimePosix, long streamTimePosix, long curPos, long totalDuration) {
			if(mWebViewFinished && mNotifiedReady) {
				if (mTimeshiftJsSrvIdx <= -1) {
					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							getJsServiceIdx(mTimeshiftPlayer.getRadioService(), new ValueCallback<String>() {
								@Override
								public void onReceiveValue(String value) {
									if (value != null) {
										if (!value.equals("null")) {
											try {
												mTimeshiftJsSrvIdx = Integer.parseInt(value);
												addActiveServiceJs(mTimeshiftJsSrvIdx);
												//registering the live metadatahandler to the running service
												mTimeshiftPlayer.getRadioService().subscribe(mMetadataHandler = new RadioMetadataHandler(mTimeshiftJsSrvIdx));

												if (DEBUG) Log.d(TAG, "Init getting TsSrvIdx: " + mTimeshiftJsSrvIdx + " and setting active service");
											} catch (NumberFormatException numExc) {
												if (DEBUG) numExc.printStackTrace();
											}
										} else {
											if (DEBUG)
												Log.d(TAG, "ValueCallback for progress is: " + value);
										}
									} else {
										if (DEBUG) Log.d(TAG, "ValueCallback for progress is null");
									}
								}
							});
						}
					});
				} else {
					final String progressJson = "{ realTime: " + realTimePosix + ",  streamTime: " + streamTimePosix + ", currentPosition: " + curPos + ", totalDuration: " + totalDuration + " }";
					if (DEBUG) Log.d(TAG, "ProgressJson: " + progressJson);
					final String callTsProgressCb = "javascript:" +
							"if(radioWeb != null) {" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.sbtProgress.forEach(function(cb) { cb(" + progressJson + "); });" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.currentPosition = " + curPos + ";" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.totalDuration = " + totalDuration + ";" +
							"}";

					//must be executed in the main thread
					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callTsProgressCb, null);
						}
					});
				}
			}
		}

		@Override
		public void started() {
			if (DEBUG) Log.d(TAG, "TS started");
			if (mWebViewFinished && mNotifiedReady) {

				if (mTimeshiftJsSrvIdx >= 0) {
					String timeshiftToken = "";
					long sbtMax = -1;
					if(mTimeshiftPlayer.getRadioService().getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_EDI) {
						timeshiftToken = ((RadioServiceDabEdi)mTimeshiftPlayer.getRadioService()).getSbtToken();
						sbtMax = ((RadioServiceDabEdi)mTimeshiftPlayer.getRadioService()).getSbtMax();
					}

					final String tsStartedJson = "{ timeshiftState: 'started' }";
					final String callTsStartedCb = "javascript:" +
							"if(radioWeb != null) {" +
							//"radioWeb.javaCall(JSON.stringify({msg: 'TimeshiftStarted call', task: 'logBack'}));" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.tsState.forEach(function(cb) { cb(" + tsStartedJson + "); });" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.paused = false;" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.timeshiftToken = '" + timeshiftToken + "';" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.sbtMax = " + sbtMax + ";" +
							"}";

					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callTsStartedCb, null);
						}
					});
				} else {
					if (DEBUG) Log.d(TAG, "TS started but mTimeshiftJsSrvIdx < 0");
					mPrematureStart = true;
				}
			} else {
				if (DEBUG) Log.d(TAG, "TS started but !mWebViewFinished or !mNotifiedReady");
			}
		}

		@Override
		public void paused() {
			if(DEBUG)Log.d(TAG, "TS paused");

			if(mWebViewFinished && mNotifiedReady) {
				if (mTimeshiftJsSrvIdx >= 0) {
					final String tsPausedJson = "{ timeshiftState: 'paused' }";
					final String callTsPauseCb = "javascript:" +
							"if(radioWeb != null) {" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.tsState.forEach(function(cb) { cb(" + tsPausedJson + "); });" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.paused = true;" +
							"}";

					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callTsPauseCb, null);
						}
					});
				}
			}
		}

		@Override
		public void stopped() {
			if(mWebViewFinished && mNotifiedReady) {
				if (mTimeshiftJsSrvIdx >= 0) {
					final String tsStoppedJson = "{ timeshiftState: 'stopped' }";
					final String callTsStoppedCb = "javascript:" +
							"if(radioWeb != null) {" +
							"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.tsState.forEach(function(cb) { cb(" + tsStoppedJson + "); });" +
							"}";

					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callTsStoppedCb, null);
						}
					});
				}
			}
		}

		@Override
		public void textual(Textual textual) {
			if (mTimeshiftJsSrvIdx >= 0) {
				try {
					final String callTsDlsCb = "javascript:" +
							"if(radioWeb != null) {" +
								"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.tsTextual.forEach(function(cb) { cb(" + createTextualJson(textual).toString() + "); });" +
							"}";

					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callTsDlsCb, null);
						}
					});
				} catch(JSONException jsonExc) {
					if(DEBUG)jsonExc.printStackTrace();
				}
			}
		}

		@Override
		public void visual(Visual visual) {
			if (mTimeshiftJsSrvIdx >= 0) {
				try {
					final String callTsSlsCb = "javascript:" +
							"if(radioWeb != null) {" +
								"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.tsVisual.forEach(function(cb) { cb(" + createVisualJson(visual).toString() + "); });" +
							"}";

					executeOnMainThread(new Runnable() {
						@Override
						public void run() {
							evaluateJavascript(callTsSlsCb, null);
						}
					});
				} catch(JSONException jsonExc) {
					if(DEBUG)jsonExc.printStackTrace();
				}
			}
		}

		@Override
		public void skipItemAdded(SkipItem skipItem) {
			if(DEBUG)Log.d(TAG, "TS skipItemAdded: " + mTimeshiftJsSrvIdx + " : " + skipItem.getSkipTextual().getText() + " : " + skipItem.getSbtRealTime());

			if (mTimeshiftJsSrvIdx >= 0) {
				try {
					JSONObject skipJsonObj = createSkipitemJson(skipItem);
					if(skipJsonObj != null) {
						if(DEBUG)Log.d(TAG, "TS skipItemAdded calling JS layer");

						final String addSkipItemCall = "javascript:" +
								"if(radioWeb != null) {" +
									//"numItems = 'numItems: ' + radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.skipItems.length + ', listeners: ' + radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.skipItemAdded.length;" +
									//"radioWeb.javaCall(JSON.stringify({msg: numItems, task: 'logBack'}));" +
 									"newSkipItemsLen = radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.skipItems.push(" + createSkipitemJson(skipItem).toString() + ");" +
									"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.skipItemAdded.forEach(function(cb) { cb(" + "radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.skipItems[newSkipItemsLen-1]" + "); });" +
								"}";

						executeOnMainThread(new Runnable() {
							@Override
							public void run() {
								evaluateJavascript(addSkipItemCall, null);
							}
						});
					}
				} catch(JSONException jsonExc) {
					if(DEBUG)jsonExc.printStackTrace();
				}
			}
		}

		@Override
		public void skipItemRemoved(SkipItem skipItem) {
			if(DEBUG)Log.d(TAG, "TS skipItemRemoved");

			if (mTimeshiftJsSrvIdx >= 0) {

				final String remSkipItemCall = "javascript:" +
						"if(radioWeb != null) {" +
							"itemIdx = radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.skipItems.map(function(item) { return item.sbtRealTime; }).indexOf(" + skipItem.getSbtRealTime() + ");" +
							"if(itemIdx > -1) { " +
								"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.listeners.skipItemRemoved.forEach(function(cb) { cb(" + "radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.skipItems[itemIdx]" + "); });" +
								"radioWeb.services[" + mTimeshiftJsSrvIdx + "].timeshift.skipItems.splice(itemIdx, 1);" +
							"}" +
						"}";

				executeOnMainThread(new Runnable() {
					@Override
					public void run() {
						evaluateJavascript(remSkipItemCall, null);
					}
				});
			}
		}
	};

}
