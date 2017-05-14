(ns orcpub.dnd.e5.db
  (:require [orcpub.route-map :as route-map]
            [orcpub.dnd.e5.template :as t5e]
            [orcpub.dnd.e5.character :as char5e]
            [re-frame.core :as re-frame]
            [orcpub.entity :as entity]
            [cljs.spec :as spec]
            [cljs.reader :as reader]
            [bidi.bidi :as bidi]))

(def local-storage-character-key "char-meta")

(def default-route route-map/dnd-e5-char-builder-route)

(defn parse-route []
  (let [{:keys [handler] :as parsed} (bidi/match-route route-map/routes js/window.location.pathname)]
    (prn "PATH" js/window.location.pathname parsed handler)
    (if handler
      handler
      default-route)))

(def default-value
  {:builder {:character {:tab #{:build :options}}}
   :character (char5e/set-class t5e/character :barbarian 0 t5e/barbarian-option)
   :template t5e/template
   :plugins t5e/plugins
   :locked-components #{}
   :route (parse-route)
   :route-history (list default-route)
   :registration-form {:send-updates? true}})

(defn character->local-store [character]
  (.setItem js/window.localStorage local-storage-character-key (str character)))

(def tab-path [:builder :character :tab])

(defn get-stored-character []
  (let [stored-str (.getItem js/window.localStorage local-storage-character-key)]
    (if stored-str
      (try (reader/read-string stored-str)
           (catch js/Object e (js/console.warn "UNREADABLE CHARACTER FOUND" stored-str))))))

(re-frame/reg-cofx
  :local-store-character
  (fn [cofx _]
      "Read in character from localstore, and process into a map we can merge into app-db."
    (assoc cofx
           :local-store-character
             (let [stored-character (get-stored-character)]
               (if stored-character
                 (if (spec/valid? ::entity/raw-entity stored-character)
                   stored-character
                   (js/console.warn "INVALID CHARACTER FOUND, IGNORING" (spec/explain-data ::entity/raw-entity stored-character))))))))