(ns starfish.core
  (:require [clojure.walk :refer [keywordize-keys stringify-keys]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [starfish.utils :refer [error TODO]])
  (:import [java.nio.charset
            StandardCharsets]
           [java.io InputStream File] 
           [java.time
            Instant]
           [java.lang IllegalArgumentException] 
           [clojure.lang
            IFn]
           [sg.dex.crypto
            Hash]
           [sg.dex.starfish.util
            DID Hex Utils RemoteAgentConfig ProvUtil DDOUtil JSON]
           [sg.dex.starfish
            Asset DataAsset Invokable Agent Job Listing Resolver Operation Purchase]
           [sg.dex.starfish.impl.memory
            MemoryAsset ClojureOperation MemoryAgent]
           [sg.dex.starfish.impl.file
            FileAsset]
           [sg.dex.starfish.impl.remote
            RemoteAgent RemoteAccount]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def BYTE-ARRAY-CLASS (Class/forName "[B"))

;; TODO: use proper public APi to get resolver instance
(def ^{:dynamic true :tag Resolver}  *resolver* (sg.dex.starfish.impl.memory.LocalResolverImpl.))

(declare content asset? get-asset get-agent)

;;===================================
;; Starfish predicates

(defn asset?
  "Returns true if the argument is an Asset"
  ([a]
   (instance? Asset a)))

(defn agent?
  "Returns true if the argument is an Agent"
  ([a]
   (instance? Agent a)))

(defn did?
  "Returns true if the argument is a W3C DID"
  ([a]
   (instance? DID a)))

(defn job?
  "Returns true if the argument is a Job"
  ([a]
   (instance? Job a)))

;;===================================
;; Utility functions, coercion etc.

(defn- json-key-fn
  "Convert to string function - suitable for producing JSON keys from Clojure symbols and keywords"
  ^String [k]
  (cond
    (symbol? k) (name k)
    (keyword? k) (subs (str k) 1) ;; do NOT interpret / for namespaced keywords
    :else (str k)))

(defn json-string
  "Coerces the argument to a valid JSON string.
   Optional pprint parameter may be used to pretty-print the JSON (default false)"
  (^String [json]
   (json-string json false))
  (^String [json pprint?]
   (let [write-json (if pprint?
                      #(with-out-str (json/pprint %))
                      #(json/write-str % :key-fn json-key-fn))]
     (cond
       (string? json) (json/write-str json)
       (map? json) (write-json json)
       (vector? json) (write-json json)
       (number? json) (write-json json)
       (boolean? json) (write-json json)
       (instance? java.util.Map json) (write-json (into {} json))
       (nil? json) (write-json json)
       :else (error "Can't convert to JSON: " (class json))))))

(defn json-string-pprint
  "Coerces the argument to a pretty-printed JSON string"
  (^String [json]
   (json-string json true)))

(defn read-json-string
  "Parses JSON string to a Clojure Map. Converts map keys to Clojure keywords."
  ([json-str]
   (json/read-str json-str :key-fn keyword)))

(defn to-bytes
  "Coerces the data to a byte array.
    - byte arrays are returned unchanged
    - Strings converted to UTF-8 byte representation
    - Assets have their raw byte content returned"
  (^{:tag bytes} [data]
   (cond
     (bytes? data) ^bytes data
     (string? data) (.getBytes ^String data StandardCharsets/UTF_8)
     (asset? data) (.getContent ^Asset data)
     :else (error "Can't convert to bytes: " (class data)))))

(defn to-string
  "Coerces data to a string format."
  (^String [data]
   (cond
     (bytes? data) (String. ^bytes data StandardCharsets/UTF_8)
     (string? data) data
     (asset? data) (to-string (content data))
     :else (error "Can't convert to string: " (class data)))))

(defn hex->bytes
  "Convert hex string to bytes"
  (^bytes [^String h]
   (Hex/toBytes h)))

(defn bytes->hex
  "Convert bytes to hex string"
  (^String [^bytes b]
   (Hex/toString b)))

(defn int->hex
  "Convert int to hex string"
  (^String [^long i]
   (Hex/toString i)))

(defn hex->int
  "Convert hex string to int"
  (^long [^String h]
   (Long/parseLong h 16)))

(defn random-hex-string
  "Creates a random hex string of the specified length in bytes"
  [len]
  (Utils/createRandomHexString len))

;; =================================================
;; Identity

(defn did
  "Gets the DID for the given input
   - DID is returned for Agents or Assets
   - Strings are intrepreted as DIDs if possible"
  (^DID [a]
   (cond
     (did? a)    a
     (asset? a)  (.getDID ^Asset a)
     (agent? a)  (.getDID ^Agent a)
     (string? a) (DID/parse ^String a)
     :else (throw (IllegalArgumentException. (str "Can't get DID: " (class a)))))))

(defn random-did-string
  "Creates a random DEP-compliant DID as a string, of the format:
  did:xxx:a1019172af9ae4d6cb32b52193cae1e3d61c0bcf36f0ba1cd30bf82d6e446563"
  (^String []
   (DID/createRandomString)))

(defn random-did
  "Creates a random DEP-compliant DID of the format:
  did:xxx:a1019172af9ae4d6cb32b52193cae1e3d61c0bcf36f0ba1cd30bf82d6e446563"
  (^DID []
   (DID/createRandom)))

(defn valid-did?
  "Returns truthy if the value is a DID or can be sucessfully coerced to a DID, falsey otherwise."
  [a]
  (or (did? a)
      (try
        (did a)
        true
        (catch Exception _
          false))))

(defn did-scheme
  "Return the DID scheme"
  (^String [a]
    (.getScheme (did a))))

(defn did-method
  "Return the DID method"
  (^String [a]
   (.getMethod (did a))))

(defn did-id
  "Return the DID ID. In standard Starfish usage, this is the ID of the Agent."
  (^String [a]
   (.getID (did a))))

(defn did-path
  "Return the DID path. In standard Starfish usage, this is equivalent to the Asset ID."
  (^String [a]
   (.getPath (did a))))

(defn did-fragment
  "Return the DID fragment"
  (^String [a]
    (.getFragment (did a))))

(defn asset-id
  "Gets the Asset ID for an asset or DID as a String.

   The asset ID is meaningful mainly  in the context of an Agent that has the Asset registered. It is
   preferable to use (did asset) for the asset DID if the intent is to obtain a full reference to the asset
   that includes the agent location."
  (^String [a]
   (cond 
     (asset? a) (.getAssetID ^Asset a)
     (did? a) (or (did-path ^DID a) (error "DID does not contain an Asset ID in DID path"))
     (string? a) (asset-id (did a))
     (nil? a) (error "Can't get Asset ID of null value") 
     :else (error "Can't get asset ID of type " (class a)))))

;; ============================================================
;; DDO management

(defn install-ddo 
  "Installs a DDO for an agent.

   DDO may be either a String or a Map, it will be coerced into a JSON String for installation."
  [did-value ddo]
  (let [^Resolver resolver *resolver*
        ^String ddo-string (cond
                             (string? ddo) ddo 
                             (map? ddo) (json-string-pprint ddo)
                             :else (error "ddo value must be a String or Map"))
        did (did did-value)]
    (.registerDID resolver did ddo-string)))

(defn ddo-string
  "Gets a DDO for the given DID as a JSON formatted String. Uses the default resolver if resolver is not specified."
  (^String [did-value]
   (ddo-string *resolver* did-value))
  (^String [^Resolver resolver did-value]
   (let [^DID d (did did-value)]
     (.getDDOString resolver d))))

(defn ddo 
  "Gets a DDO for the given DID as a Clojure map. Uses the default resolver if resolver is not specified."
  (^String [did-value]
    (ddo *resolver* did-value))
  (^String [^Resolver resolver did-value]
    (if-let [ddos (ddo-string resolver did-value)] 
      (read-json-string ddos))))

(defn create-ddo
  "Creates a default DDO as a String for the given host address"
  (^String [host]
   (DDOUtil/getDDO host)))


;; =================================================
;; Account

(defn remote-account
  [^String username ^String password]
  ;; TODO: shouldn't this 
  (let [^String id (or username (error "Username required!"))
        ^java.util.Map creds {"username" username
                         "password" password}]
    (RemoteAccount/create id creds)))

;; =================================================
;; Operations

(defn create-operation
  "Create an in-memory operation with the given parameter list and function.

   The function provided should accept a map of inputs where each entry maps a keyword to either:
     a) A Starfish Asset
     b) A object representation of a JSON value as per read-json-string

   The function should return a similar map of outputs where each entry maps a keyword to either:
     a) A Starfish Asset
     b) A object representation of a JSON value as per read-json-string"
  ([params ^IFn f]
   (create-operation params f nil))
  ([params ^IFn f additional-metadata]
   (let [wrapped-fn (fn [params]
                      (f params))
         params (mapv name params)
         paramspec (reduce #(assoc %1 %2 {"type" "asset"}) {} params)
         meta {"name" "Unnamed Operation"
               "type" "operation"
               "dateCreated" (str (Instant/now))
               "operation" {"modes" ["sync" "async"]
                            "params" paramspec}}
         meta (merge meta (stringify-keys additional-metadata))]
     (ClojureOperation/create (json-string meta) (MemoryAgent/create) wrapped-fn ))))

(defn- format-params
  "Format parameters into a parameter map of string->asset according to the requirements of the operation."
  (^java.util.Map [operation params]
   (cond
     (map? params) params
     (asset? params) {:input params}
     :else (throw (IllegalArgumentException. (str "Params type not supported: " (class params)))))))

(defn invoke
  "Invoke an operation with the given parameters. Parameters may be either a positional list
   or a map of name / value pairs"
  (^Job [^Operation operation params]
   (let [params (format-params operation params)]
     (.invoke operation ^java.util.Map (stringify-keys params)))))

(defn invoke-result
  "Invoke an operation and wait for the result.

   An optional timeout may be provided."
  (^Asset [^Operation operation params]
   (let [job (invoke operation params)
         resp (.getResult job)]
     resp))
  (^Asset [^Operation operation params timeout]
   (let [job (invoke operation params)
         resp (.getResult job (long timeout))]
     resp)))


(defn invoke-sync
  "Invokes an operation synchronously, waiting to return the result."
  ([^Operation operation params]
    ;;convert from java Hashmap to Clojure map
   (into {} (.invokeResult operation params))))

(defn job-status 
  "Gets the status of a Job instance as a keyword. 

   Possible return values are defined by DEP6."
  ([^Job job]
    (keyword (.getStatus job)))) 

(defn job-id 
  "Gets the ID of a Job instance."
  ([^Job job]
    (.getJobID job))) 

(defn get-result
  "Gets the results of a job, as a map of keywords to assets / values.

   Blocks until results are ready"
  [^Job job]
  (let [res (.getResult job)]
    (keywordize-keys res))) 

(defn poll-result
  "Pools the results of a job, returning a map of keywords to assets / values if succeeded.

   Returns null if results are not yet available"
  [^Job job]
  (let [res (.pollResult job)]
    (keywordize-keys res))) 

;; ==============================================================
;; Asset functionality

(defn asset
  "Coerces input data to an asset.
   - Existing assets are unchanged
   - DIDs are resolved to appropriate assets if possible"
  (^Asset [data]
   (cond
     (asset? data) data
     (did? data) (get-asset (get-agent ^DID data) data)
     (string? data) (asset (did data))
     (nil? data) (throw (IllegalArgumentException. "Cannot convert nil to Asset")) 
     :else (error "Cannot coerce to Asset: " data))))

(defn memory-asset
  "Create an in-memory asset with the given metadata and data.

   If no metadata is supplied, default metadata is generated."
  (^Asset [data]
   (cond
     :else (let [byte-data (to-bytes data)]
             (MemoryAsset/create byte-data))))
  (^Asset [meta data]
   (let [byte-data (to-bytes data)]
     (if (string? meta) 
       (MemoryAsset/create byte-data ^String meta)
       (let [^java.util.Map meta-map (stringify-keys meta)] 
         (MemoryAsset/create byte-data meta-map ))))))

(defn file-asset
  "Create a file asset with the given metadata and source file.

   If no metadata is supplied, default metadata is generated."
  (^Asset [file]
   (let [^File file (io/file file)]
     (FileAsset/create file)))
  (^Asset [meta file]
   (let [^File file (io/file file)]
     (if (string? meta) 
       (FileAsset/create file ^String meta)
       (let [^java.util.Map meta-map (stringify-keys meta)] 
         (FileAsset/create file meta-map ))))))

;; =======================================================
;; Agent functionality

(defn remote-agent
  "Gets a remote agent with the provided DID"
  ([local-did ddo username password]
   (RemoteAgentConfig/getRemoteAgent ddo (did local-did) username password)))

(defn get-asset
  "Gets an asset from a remote agent, given an Asset ID as a String or DID."

  ([^Agent agent assetid]
    (let [^String id (if (string? assetid) assetid (asset-id assetid))]
      (.getAsset agent id)))) 

(defn get-agent
  "Gets the DEP Agent agent for the given DID"
  (^Agent [agent-did]
   (get-agent *resolver* agent-did))
  (^Agent [^Resolver resolver agent-did]
   (cond
     (agent? agent-did) agent-did
     (did? agent-did) (RemoteAgent/create resolver ^DID agent-did)
     :else (throw (IllegalArgumentException. (str "Invalid did: " (class agent-did)))))))

(defn digest
  "Computes the sha3_256 String hash of the byte representation of some data and returns this as a hex string.

  Handles
   - byte arrays - hashed as-is
   - Strings - converted to UTF-8 representation
   - Assets - compute the hash of asset metadata
  "
  (^String [data]
   (let [bytes (to-bytes data)]
     (Hash/sha3_256String bytes))))


(defn upload
  "Uploads any asset to an Agent. Registers the asset with the Agent if required.

   Returns an Asset instance referring to the uploaded remote Asset." 
  (^Asset [^Agent agent a]
   (.uploadAsset agent (asset a))))

(defn register
  "Registers an Asset with an Agent. Registration stores the metadata of the asset with the Agent, 
   but does not upload any data.

   Returns an asset associated with the agent if successful."
  (^Asset [^Agent agent a]
    (.registerAsset agent (asset a))))

(defn register-metadata
  "Registers metadata with an Agent. Registration stores the metadata with the Agent, 
   but does not upload any data.

   Returns an asset associated with the agent if successful."
  (^Asset [^Agent agent ^String meta-string]
    (.registerAsset agent meta-string)))

(defn metadata
  "Gets the metadata for an Asset as a Clojure map"
  ([a]
   (let [a (asset a)
         md (.getMetadata a)]
     (keywordize-keys (into {} md)))))

(defn metadata-string
  "Gets the metadata for an Asset as a String. This is guaranteed to match the 
   precise metadata used for the calculation of the Asset ID."
  (^String [a]
    (let [^Asset a (asset a)]
      (.getMetadataString a))))

(defn content
  "Gets the content for a given Asset as raw byte data"
  (^bytes [a]
   (let [^Asset a (asset a)]
     (.getContent a))))

(defn content-stream
  "Gets the content for a given data asset as an input stream."
  (^java.io.InputStream [a]
   (let [^Asset a (asset a)]
     (.getContentStream ^DataAsset a))))

(defn publish-prov-metadata
  "Creates provenance metadata. If the first argument is a map with raw metadata, it adds a provenance
  section"
  ([^String activityId ^String agentId]
   (publish-prov-metadata {} activityId agentId))
  ([metadata ^String activityId ^String agentId]
   (merge metadata {"provenance" (ProvUtil/createPublishProvenance activityId agentId)})))

(defn invoke-prov-metadata
  ([^String activityId ^String agentId asset-dependencies ^String params ^String result-param-name]
   (invoke-prov-metadata {} activityId agentId asset-dependencies params result-param-name))
  ([metadata ^String activityId ^String agentId asset-dependencies ^String params ^String result-param-name]
   (merge metadata {"provenance" (ProvUtil/createInvokeProvenance activityId agentId
                                                                  asset-dependencies
                                                                  params result-param-name)})))

