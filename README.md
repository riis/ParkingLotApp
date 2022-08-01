# Parking Lot App

## Adding API Keys to `local.properties` File
Two API keys are needed for the application to function correctly, a DJI sdk key and a Mapbox key.

A DJI app key can be obtained by registering an application's bundle id at https://developer.dji.com/user/apps/#all. First, create an account or sign in. Next, press `Create App`, and fill in the required information. Then, press `Create`. The app key can be viewed by clicking on the SDK project. Copy and paste this key anywhere in the `local.properties` file in the format `DJI_API_KEY=<YOUR KEY>`.

A Mapbox token can be obtained by going to https://account.mapbox.com/ and creating an account. Next, create an access token by clicking `Create Token`. For secret scopes, select `MAP:READ`. Then, press `Create token`. Copy this token anywhere into the `local.properties` file in the format `MAPBOX_DOWNLOADS_TOKEN=<YOUR  KEY>`.