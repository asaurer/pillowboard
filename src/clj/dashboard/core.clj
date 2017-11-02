(ns dashboard.core
  (:require [dashboard.inflater :as inflater]
            [dashboard.transformer :as transformer]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [clojure.core.async :as async  :refer (<! <!! >! >!! put! chan go go-loop)]
            [org.httpkit.server :refer [run-server]]
            [taoensso.sente :as sente] 
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [taoensso.timbre :as timbre :refer (tracef debugf infof warnf errorf)]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.json :refer [wrap-json-body]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
            [ring.middleware.reload :as reload]
            [ring.middleware.cors :refer [wrap-cors]]
            ))

(timbre/set-level! :trace) 

(def app-state 
  {:content {:commits {:data [[0 1 2 3 4 5 6 7 8 9] [0 1 1 2 0 2 3 1 4 2]]
                       :meta {:labels [:time :commit]}}
             :sprint {:data [[0.1 0.2 0.3 0.5 0.6 0.6 0.6 0.8 0.9 1.0 1.0 1.0]
                             [0.0 0.0 0.0 0.2 0.2 0.3 0.3 0.4 0.5 0.9 0.9 1.0]
                             [0.0 0.0 0.0 0.0 0.1 0.4 0.3 0.5 0.6 0.9 1.0 1.0]]
                      :meta {:labels [:accepted :in-progress :done]}}
             :stability {:data [[0 1 2 1 2 4 8 9 8 12 5] [0 1 12 4 2 4 8 14 22 50 49]]
                         :meta {:labels [:incidents :traffic]}}
             :pull-requests {:data [[0 1 2 3 4 5 6 7 8 9]
                                    [0 1 0 0 2 2 3 0 2 1]
                                    [0 1 1 2 2 3 2 1 2 4]]
                             :meta {:labels [:time :pull-requests :bugs]}}}
   :config {:commits {:type :line}
            :sprint {:type :area}
            :stability {:type :scatter}
            :pull-requests {:type :line}}})

(let [;; Serializtion format, must use same val for client + server:
      packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-transit-packer) ; Needs Transit dep

      chsk-server
      (sente/make-channel-socket-server!
       (get-sch-adapter) {:packer packer :user-id-fn (fn [req] "some-custom-id-42")})

      {:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      chsk-server]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(add-watch connected-uids :connected-uids
  (fn [_ _ old new]
    (when (not= old new)
      (infof "Connected uids change: %s" new))))

(defroutes cors-routes
  (POST "/dashboard" req (str "you posted: " req)))

(defroutes secure-routes
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post                req))
  (route/resources "/")
  (route/not-found "<h1>Page not found</h1>"))

(def cors-handlers
  (-> cors-routes
      (wrap-cors :access-control-allow-methods [:post])
      (wrap-json-body)
      (wrap-defaults api-defaults)))

(def secure-handlers
  (-> secure-routes
      (reload/wrap-reload)
      (wrap-defaults site-defaults)
      ))

(def all-handlers
  (routes
   cors-routes
   secure-routes))

(defn make-renderable
  [data]
  (-> data
      (inflater/inflate)
      (transformer/transform)))

(defn start-example-broadcaster!
  "As an example of server>user async pushes, setup a loop to broadcast an
  event to all connected users every 10 seconds"
  []
  (let [broadcast!
        (fn [i]
          (let [uids (:any @connected-uids)]
            (debugf "Broadcasting server>user: %s uids" (count uids))
            (doseq [uid uids]
              (chsk-send! uid
                [:board/state
                 {:state (make-renderable app-state)}]))))]

    (go-loop [i 0]
      (<! (async/timeout 10000))
      (broadcast! i)
      (recur (inc i)))))

(defn -main [& args]
  (run-server all-handlers {:port 3000})
  (start-example-broadcaster!)
  (infof "Web server is running at http://localhost:3000"))
