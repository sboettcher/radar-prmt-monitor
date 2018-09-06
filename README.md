# pRMT Device Monitor
Application to monitor incoming data to a RADAR platform server, by opening a REST proxy consumer on the Kafka stack.

![Screenshot radar-prmt-monitor](/man/screen-2018-09-05-174925_edit.png)

## Setup
1. Go into `radar-prmt-monitor/app/src/main/res/xml`.
2. Make a copy of `remote_config_defaults_template.xml` named `remote_config_defaults.xml`.
3. Edit the placeholder server URL in `remote_config_defaults.xml`. This should be the URL to the RADAR-base platform server you want to connect to.

## WIP
This application is still work in progress. There may be bugs, unexpected behaviour and crashes.
