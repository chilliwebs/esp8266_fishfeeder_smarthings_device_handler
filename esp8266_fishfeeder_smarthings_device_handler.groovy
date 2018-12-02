/**
 *  esp8266 fishfeeder Device
 *
 *  Nick Hecht 2018 (chilliwebs@gmail.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
	definition (name: "esp8266 Automatic Fish Feeder", namespace: "chilliwebs", author: "Nick Hecht") {
		capability "Actuator"
        capability "Switch"
        capability "Polling"
        capability "Refresh"
        capability "Sensor"
        
        attribute "currentIP", "string"

        command "setOffline"
    }

    simulator {}

	tiles(scale: 1) {
    	valueTile("display", "device.display", decoration: "flat", width: 2, height: 1, canChangeIcon: true, canChangeBackground: true) {
            state "display", label:'${currentValue}',
            icon: "st.Food & Dining.dining12"
        }
    
        // standard tile with actions named
        standardTile("switch", "device.switch", width: 3, height: 3, canChangeIcon: true) {
            state "off", label: 'feed', action: "switch.on",
                  icon: "st.Food & Dining.dining12", backgroundColor: "#AA00DC"
            state "on", label: 'feeding',
                  icon: "st.Food & Dining.dining12", backgroundColor: "#DCA500"
            state "offline", label:'offline', 
                  icon:"st.Food & Dining.dining12", backgroundColor:"#cccccc"
        }
        
        valueTile("last", "device.last", decoration: "flat", width: 2, height: 1) {
            state "last", label:'${currentValue}'
        }
        
        standardTile("refresh", "device.switch", inactiveLabel: false, height: 1, width: 1, decoration: "flat") {
            state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        
        valueTile("currentIP", "device.currentIP", decoration: "flat", width: 3, height: 1) {
            state "currentIP", label:'${currentValue}'
        }

        main("display")

        details(["switch", "last", "refresh", "currentIP"])
    }
}

private getCallBackAddress() {
    return device.hub.getDataValue("localIP") + ":" + device.hub.getDataValue("localSrvPortTCP")
}

private getHostAddress() {
    def ip = getDataValue("ip")
    def port = getDataValue("port")

    if (!ip || !port) {
        def parts = device.deviceNetworkId.split(":")
        if (parts.length == 2) {
            ip = parts[0]
            port = parts[1]
        } else {
            log.warn "Can't figure out ip and port for device: ${device.id}"
        }
    }

    log.debug "Using IP: $ip and port: $port for device: ${device.id}"
    return convertHexToIP(ip) + ":" + convertHexToInt(port)
}

private Integer convertHexToInt(hex) {
    return Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
    return [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

def fed() {
  sendEvent(name: "switch", value: "off", descriptionText: "The device online")
}

def on() {
	log.debug "Executing 'on'"
    sendEvent(name: "switch", value: "on", descriptionText: "The device is feeding")
    
    log.debug "Executing feed"
    def feed = new physicalgraph.device.HubAction(
        method: "GET",
        path: "/feed",
        headers: [
            HOST: getHostAddress()
        ]
    )
    
    return feed
}

def poll() {
    def result = new physicalgraph.device.HubAction(
        method: "GET",
        path: "/info",
        headers: [
            HOST: getHostAddress()
        ]
    )
    if (device.currentValue("currentIP") != "Offline") {
      runIn(10, setOffline)
    }
    return result
}

def setOffline() {
    sendEvent(name: "switch", value: "offline", descriptionText: "The device is offline")
    sendEvent(name: "display", value: "offline", descriptionText: "The device is offline")
    sendEvent(name: "currentIP", value: "offline", descriptionText: "The device is offline")
}

def installed() {
    return [setOffline(), poll()]
}

def refresh() {
    poll()
}

def parse(String description) {
    log.debug "parse '${description}'"
	def msg = parseLanMessage(description)
    def json = msg.json
    
    def lastresult
    def displayresult
    
    log.debug "json '${json}'"
    if(json?.last || json?.now) {
      log.debug "last '${json.last}'"
      log.debug "now '${json.now}'"
      if (device.currentValue("currentIP") == "offline") {
        def ipvalue = convertHexToIP(getDataValue("ip"))
        sendEvent(name: "currentIP", value: ipvalue, descriptionText: "currentIP is ${ipvalue}")
      }
      
      long diff = (long) (json.now - json.last)
	  int minutes = (long) ((long)(diff / (60l)) % 60l);
	  int hours   = (long) ((long)(diff / (60l*60l)) % 24l);
      
      if(hours > 24) {
        def curr = (new Date()).getTime()/1000
      	def last = new Date(((curr - (curr%60)) + (diff-(diff%60)))*1000)
      	lastresult = createEvent(name: "last", value: "Last Feeding: \n${last.format("h:mm a 'on' MM/d/yy")}")
      	displayresult = createEvent(name: "display", value: "${last.format("MM/d/yy")}")
      } else if (hours > 0) {
      	lastresult = createEvent(name: "last", value: "Last Feeding: \n${hours} hours ago")
      	displayresult = createEvent(name: "display", value: "~${hours} hrs ago")
      } else if (minutes > 0) {
      	lastresult = createEvent(name: "last", value: "Last Feeding: \n${minutes} minutes ago")
      	displayresult = createEvent(name: "display", value: "~${minutes} min ago")
      } else {
      	lastresult = createEvent(name: "last", value: "Last Feeding: \nfew seconds ago")
      	displayresult = createEvent(name: "display", value: "seconds ago")
      }
      
      unschedule()
      runIn(1, fed)
    }
    
    return [lastresult, displayresult]
}

def sync(ip, port) {
	log.debug "sync '${ip}' '${port}'"
	def existingIp = getDataValue("ip")
	def existingPort = getDataValue("port")
    def currentIP
	if (ip && ip != existingIp) {
		updateDataValue("ip", ip)
        def ipvalue = convertHexToIP(getDataValue("ip"))
        sendEvent(name: "currentIP", value: ipvalue, descriptionText: "currentIP changed to ${ipvalue}")
	}
	if (port && port != existingPort) {
		updateDataValue("port", port)
	}
}