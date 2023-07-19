package com.datapipeline.collector.metric

import spock.lang.Specification


class ConfigSpec extends Specification {

    def "test config getters and setters"() {
        setup: "An instance of Config and set properties using setters"
        def config = new Config()
        config.setAppName("example")
        config.setEndpoint("localhost:4137")
        config.setInstance("dp1")
        config.setNamespace("runtime")
        config.setService("worker")
        config.setInterval(30)
        config.setTimeout(30)
        expect: "Properties should be equal to the set values using getters"
        config.getAppName() == "example"
        config.getEndpoint() == "localhost:4137"
        config.getInstance() == "dp1"
        config.getNamespace() == "runtime"
        config.getService() == "worker"
        config.getInterval() == 30
        config.getTimeout() == 30
    }

}
