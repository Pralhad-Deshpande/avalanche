# Avalanche Simulation

Experimental simulation of the Avalanche protocol by Team Rocket. This
implementation is incomplete and likely incorrect.

The paper: [Snowflake to Avalanche: A Novel Metastable Consensus Protocol Family for
           Cryptocurrencies](https://ipfs.io/ipfs/QmUy4jh5mGNZvLkjies1RWM4YuvJh5o2FYopNPVYwrRVGV).

## Running the Simulation
```
./gradlew run
```

### Visualising the DAGs
```
for f in node-0-*.dot; do dot -Tpng -O $f; done
```
The above command generates a number of PNG files `node-0-*.png`.
