This project contains Yamcs Data Links for enabling yamcs to receive data from an SLE provider.
For the moment only FCLTU (Forward CLTU) and RAF (Return All Frames) are supported.

## Installation and test

To test it:
- clone the repository
- change the SLE providers in the src/main/yamcs/etc/sle.yaml 
- change the TM/TC frames parameers in the src/main/yamcs/etc/yamcs.sle.yaml, dataLinks section
- run:

```
mvn yamcs:run
```

This will create one instance called sle. Open a webbrowser connected to http://localhost:8090/, select the sle instance and navigate to the "Links" in the left menu. Also open another window showing the Monitoring -> Events. Disabling/Enabling the link will cause the connection to be closed/estabilished. Once the connection is estabilished, it will immediately perfom the bind SLE operation and if that is successful, the start.



## TODO:
- make a REST interface to allow specifying start/stop times for retrieving offline data.
- send parameters of the SLE as yamcs parameters to allow monitoring the status in the normal displays.
- implement the RCF (this should be very simple)


![](yamcs-connected-to-sle.png?raw=true)
