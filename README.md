Yahoo! Cloud System Benchmark (YCSB)
====================================

Links
-----
http://wiki.github.com/brianfrankcooper/YCSB/  
http://research.yahoo.com/Web_Information_Management/YCSB/  
ycsb-users@yahoogroups.com  

Getting Started
---------------

1. Download the latest release of YCSB:

2. Set up a database to benchmark. There is a README file under each binding 
   directory.

3. Run YCSB command. 
    
    ```sh
    bin/ycsb load basic -P workloads/workloada
    bin/ycsb run basic -P workloads/workloada
    ```

  Running the `ycsb` command without any argument will print the usage. 
   
  See https://github.com/brianfrankcooper/YCSB/wiki/Running-a-Workload
  for a detailed documentation on how to run a workload.

  See https://github.com/brianfrankcooper/YCSB/wiki/Core-Properties for 
  the list of available workload properties.
