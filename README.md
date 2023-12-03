
# CNT4007 Project


P2P network implementation with Java


## Authors

- [@Daniel Moraes](https://github.com/Daniel-Moraes1)
- [@Alexander Decious](https://github.com/adeci)
- [@Ryan Houston](https://github.com/ryanh6900)



## Compilation

To compile the binaries and generate executable P2PNet.jar file

```bash
  make
```

To cleanly remove compiled files run
```bash
  make clean
```

To execute run
```bash
  java -jar P2PNet.jar {peer id goes here}
```


## Network Configuration

Copy an alternative configuration into the main Config folder

| Config Preset | Transfer File     | Description                |
| :-------- | :------- | :------------------------- |
| project_config_file_small | thefile | **1.76MB** file initially only on peers 1001 and 1006|
| project_config_file_large | tree.jpg | **23.1MB** png file initially only on peer 1001|

#### Peers must be initialized to the network in ascending order
