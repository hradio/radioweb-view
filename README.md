# RadioWebView Component

A WebView component to develop HRadio Apps with HTML/JavaScript

## API

### General

The main object when loading the RadioWeb-App is the `radioWeb` object.  
You shall register at first a callback to get notified when the `radioWeb` object is ready with `window.addEventListener('radioWebReady', callBackFunction)`

The `radioWeb` object has the following properties:

| Property | Type | Possible Values / Description |
| --- | :---: | :---: |
| status | string | STATUS_RADIO_RUNNING, STATUS_RADIO_SUSPENDED |
| volume | int | percent value 0 - 100 |
| activeServices | service object array | array of currently active services |
| services | service object array | array of all available services |

The `radioWeb` object has the following methods:

| Method | Parameter | Description / Remarks |
| --- | :---: | :---: |
| addEventListener(`type`, `callback`) | `type` is one of 'serviceStarted', 'serviceStopped, 'servicesUpdated', `callback` the function to call on event | the callback function shall look like `function cb(startedServiceObject){}` |
| removeEventListener(`type`, `callback`) | `type` is one of 'serviceStarted', 'serviceStopped, 'servicesUpdated', the `callback` function to remove | the callback function shall look like `function cb(startedServiceObject){}` |
| setVolume(`newVolume`) | `newVolume` is a integer value between 0 and 100 | sets the volume |

A `service` has the following properties:

| Property | Type | Possible Values / Description |
| --- | :---: | :---: |
| type | string | RADIOSERVICE_TYPE_DAB, RADIOSERVICE_TYPE_EDI, RADIOSERVICE_TYPE_IP, RADIOSERVICE_TYPE_FM,  RADIOSERVICE_TYPE_SIRIUS, RADIOSERVICE_TYPE_HDRADIO |
| serviceLabel | string | the service label |
| timeshifted | boolean | indicates if the service is timeshifted or not (currently only DAB or EDI services are timeshiftable) |

If the `service.type` is RADIOSERVICE_TYPE_DAB or RADIOSERVICE_TYPE_EDI the service has the following additional properties:

| Property | Type | Possible Values / Description |
| --- | :---: | :---: |
| serviceId | int | the DAB service ID |
| ensembleId | int | the ensemble id |
| ensembleEcc | int | the extended country code for the ensemble |
| ensembleLabel | string | the label of the ensemble |
| ensembleFrequency | int | the frequency of the ensemble in kHz |
| isProgramme | bool | indicates if the service is a programme (audio) or data service |

A service has the following methods:

| Method | Parameter | Description / Remarks |
| --- | :---: | :---: |
| start() | | starts the service |
| stop() | | stops the service |
| addEventListener(`type`, `callback`) | `type` is one of 'sls', 'dls', 'epg', 'state', `callback` the function to call on event | the callback function shall look like `function cb(eventJson){}` |
| removeEventListener(`type`, `callback`) | `type` is one of 'sls', 'dls', 'epg', 'state', `callback` the function to call on event | the callback function shall look like `function cb(eventJson){}` |

The JSON Objects on service events:
 
DLS JSON: 
```json
{
    "textualType": "METADATA_TEXTUAL_TYPE_DAB_DLS",
    "dls": "Sprechstunde - Hörertelefon 00800 44644464",
    "itemRunning": true,
    "itemToggled": false,
    "dlPlusItems": [{
        "contentType": "PROGRAMME_NOW",
        "contentCategory": "PROGRAMME_NOW",
        "contentTypeDescription": "Now",
        "dlPlusText": "Sprechstunde - Hörertelefon 00800 44644464"
    }]
}
```

SLS JSON: 
```json
{
    "visualType": "METADATA_VISUAL_TYPE_DAB_SLS",
    "contentName": "0701.png",
    "slideId": 1,
    "triggerTime": "NOW",
    "mimeType": "image/png",
    "isCategorized": true,
    "categoryId": 7,
    "categoryName": "Kontakt",
    "clickthroughUrl": "http://www.deutschlandfunk.de",
    "alternativeLocationUrl": "",
    "expiryTime": 0,
    "visualData": "binary BASE64 encoded image data"
}
```

### Timeshift

If the RadioWebView is running as a component of an App which has timeshift functionalities and the RadioWebView has a reference to the running TimeshiftPlayer, the currently timeshifted 
RadioService has an additional object `timeshift`.

The `timeshift` object has the following properties:

| Property | Type | Possible Values / Description |
| --- | :---: | :---: |
| currentPosition | int | the current position in the timeshift buffer in seconds |
| totalDuration | int | the total duration of the timeshift buffer in seconds since the start of timeshift |
| paused | boolean | indicates the current state of the timeshiftplayer. `true` if the player is paused, `false` otherwise |
| skipItems | skipItem  object array | array of the available `skipItem`s |
| timeshiftToken | string | a unique token string for the server based timeshift (SBT) |
| sbtMax | int | indicates the maximum SBT buffer in milliseconds |

The `timeshift` object has the following methods:

| Method | Parameter | Description / Remarks |
| --- | :---: | :---: |
| pause(`pauseUnpause`) | `pauseUnpause` boolean value. `true` to pause the player, `false` to make it play agein| pauses / unpauses the timeshiftplayer |
| seek(`seekMs`) | `seekMs` the position in the timeshift buffer to seek to in milliseconds | seeks the timeshiftplayer to a new position |
| skipTo(`skipToItem`) | `skipToItem`, a `skipItem` object to skip to its start position | skips the timeshiftplayer to the beginning of the given `skipItem` |
| addTimeshiftListener(`type`, `callback`) | `type` is one of 'state', 'skipitemadded', 'skipitemremoved', 'progress', 'sbtprogress', 'visual', 'textual', `callback` the function to call on event | the callback function shall look like `function cb(eventJson){}` |
| removeTimeshiftListener(`type`, `callback`) | `type` is one of 'state', 'skipitemadded', 'skipitemremoved, 'progress', 'sbtprogress', 'visual', 'textual', `callback` the function to remove from event notififcations | removes the previously registered callback function |

### Server Based Timshift

If a started radioservice has support for server based timeshift (SBT) there may be SkipItems right from the start 
or added rapidly via the 'skipitemadded' callback.

For the SBT functionality you should register a timeshiftlistener with:
addTimeshiftListener(`sbtprogress`, `callback`)

The registered callback gives a JSON in the following form:
SBT JSON: 
```json
{
    "realTime": 1575376717895,
    "streamTime": 1575373118860,
    "currentPosition": 3600,
    "totalDuration": 7200
}
```
`realTime` is the current real-world POSIX timestamp in milliseconds
`streamTime` is the current POSIX timestamp of the stream in milliseconds
`currentPosition` is the current relative position in the SBT buffer in seconds
`totalDuration` is the total relative SBT buffer in seconds

Also a 'skipitemremoved' callback should be registered to be notified when skipitems are not valid anymore.

