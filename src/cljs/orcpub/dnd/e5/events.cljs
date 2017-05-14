(ns orcpub.dnd.e5.events
  (:require [orcpub.entity :as entity]
            [orcpub.template :as t]
            [orcpub.common :as common]
            [orcpub.dice :as dice]
            [orcpub.dnd.e5.template :as t5e]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.db :refer [default-value character->local-store tab-path]]

            [re-frame.core :refer [reg-event-db reg-event-fx reg-fx inject-cofx path trim-v
                                   after debug dispatch]]
            [cljs.spec :as spec]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [clojure.string :as s]
            [bidi.bidi :as bidi]
            [orcpub.route-map :as routes]
            [orcpub.errors :as errors])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn check-and-throw
  "throw an exception if db doesn't match the spec"
  [a-spec db]
  (when-not (spec/valid? a-spec db)
    (throw (ex-info (str "spec check failed: " (spec/explain-str a-spec db)) {}))))

(def check-spec-interceptor (after (partial check-and-throw ::entity/raw-entity)))

(def ->local-store (after character->local-store))

(def db-char->local-store (after (fn [db] (character->local-store (:character db)))))

(def character-interceptors [check-spec-interceptor
                             (path :character)
                             ->local-store])


;; -- Event Handlers --------------------------------------------------

(reg-event-fx
 :initialize-db
 [(inject-cofx :local-store-character)
  check-spec-interceptor]
 (fn [{:keys [db local-store-character]} _]
   {:db (if (seq db)
          db
          (if local-store-character
            (assoc default-value :character local-store-character)
            default-value))}))

(defn reset-character [_ [_]]
  (char5e/set-class t5e/character :barbarian 0 t5e/barbarian-option))

(reg-event-db
 :reset-character
 character-interceptors
 reset-character)

(defn set-character [db [_ character]]
  ;;(prn "SET_CHARACTER" character)
  (assoc db :character character :loading false))

(reg-event-db
 :set-character
 [db-char->local-store]
 set-character)

(def character-values-path
  [::entity/values])

(defn character-value-path [prop-name]
  (conj character-values-path prop-name))

(defn update-value-field [character [_ prop-name value]]
  (assoc-in character (character-value-path prop-name) value))

(reg-event-db
 :update-value-field
 character-interceptors
 update-value-field)

(defn add-class [character [_ first-unselected]]
  (update-in
   character
   [::entity/options :class]
   conj
   {::entity/key first-unselected ::entity/options {:levels [{::entity/key :level-1}]}}))

(reg-event-db
 :add-class
 character-interceptors
 add-class)

(reg-event-db
 :set-image-url
 character-interceptors
 (fn [character [_ image-url]]
   (update character
           ::entity/values
           assoc
           :image-url
           image-url
           :image-url-failed
           (if (s/starts-with? image-url "https")
             :https))))

(reg-event-db
 :set-faction-image-url
 character-interceptors
 (fn [character [_ faction-image-url]]
   (update character
           ::entity/values
           assoc
           :faction-image-url
           faction-image-url
           :faction-image-url-failed
           (if (s/starts-with? faction-image-url "https")
             :https))))

(def custom-equipment-path [::entity/values :custom-equipment])

(def custom-treasure-path [::entity/values :custom-treasure])

(defn add-starting-equipment [character [_ equipment-options custom-treasure custom-equipment]]
  (-> character
      (char5e/remove-starting-equipment :background-starting-equipment?)
      (char5e/add-associated-options equipment-options)
      (char5e/remove-custom-starting-equipment :background-starting-equipment? custom-treasure-path)
      (char5e/add-custom-equipment custom-treasure custom-treasure-path)
      (char5e/remove-custom-starting-equipment :background-starting-equipment? custom-equipment-path)
      (char5e/add-custom-equipment custom-equipment custom-equipment-path)))

(reg-event-db
 :add-starting-equipment
 character-interceptors
 add-starting-equipment)

(defn set-class [character [_ class-key class-index options-map]]
  (char5e/set-class character class-key class-index (options-map class-key)))

(reg-event-db
 :set-class
 character-interceptors
 set-class)

(defn set-class-level [character [_ class-index new-highest-level]]
  (update-in
   character
   [::entity/options :class class-index ::entity/options :levels]
   (fn [levels]
     (let [current-highest-level (count levels)]
       (cond
         (> new-highest-level current-highest-level)
         (vec (concat levels (map
                              (fn [lvl] {::entity/key (keyword (str "level-" (inc lvl)))})
                              (range current-highest-level new-highest-level))))
         
         (< new-highest-level current-highest-level)
         (vec (take new-highest-level levels))
         
         :else levels)))))

(reg-event-db
 :set-class-level
 character-interceptors
 set-class-level)

(defn delete-class [character [_ class-key i options-map]]
  (let [updated (update-in
                 character
                 [::entity/options :class]
                 (fn [classes] (vec (remove #(= class-key (::entity/key %)) classes))))
        new-first-class-key (get-in updated [::entity/options :class 0 ::entity/key])
        new-first-class-option (if new-first-class-key (options-map new-first-class-key))]
    (if (and (zero? i)
             new-first-class-option)
      (char5e/set-class updated new-first-class-key 0 new-first-class-option)
      updated)))

(reg-event-db
 :delete-class
 character-interceptors
 delete-class)

(defn add-inventory-item [character [_ selection-key item-key]]
  (update-in
   character
   [::entity/options selection-key]
   (fn [items]
     (vec
      (conj
       items
       {::entity/key item-key
        ::entity/value {:quantity 1 :equipped? true}})))))

(reg-event-db
 :add-inventory-item
 character-interceptors
 add-inventory-item)

(defn toggle-inventory-item-equipped [character [_ selection-key item-index]]
  (update-in
   character
   [::entity/options selection-key item-index ::entity/value :equipped?]
   not))

(reg-event-db
 :toggle-inventory-item-equipped
 character-interceptors
 toggle-inventory-item-equipped)

(defn toggle-custom-inventory-item-equipped [character [_ custom-equipment-key item-index]]
  (update-in
   character
   [::entity/values custom-equipment-key item-index :equipped?]
   not))

(reg-event-db
 :toggle-custom-inventory-item-equipped
 character-interceptors
 toggle-custom-inventory-item-equipped)

(defn change-inventory-item-quantity [character [_ selection-key item-index quantity]]
  (update-in
   character
   [::entity/options selection-key item-index ::entity/value]
   (fn [item-cfg]
     ;; the select keys here is to keep :equipped while wiping out the starting-equipment indicators
     (assoc (select-keys item-cfg [:equipped?]) :quantity quantity))))

(reg-event-db
 :change-inventory-item-quantity
 character-interceptors
 change-inventory-item-quantity)

(defn change-custom-inventory-item-quantity [character [_ custom-equipment-key item-index quantity]]
  (update-in
   character
   [::entity/values custom-equipment-key item-index]
   (fn [item-cfg]
     ;; the select keys here is to keep :equipped and :name while wiping out the starting-equipment indicators
     (assoc
      (select-keys item-cfg [:name :equipped?])
      :quantity
      quantity))))

(reg-event-db
 :change-custom-inventory-item-quantity
 character-interceptors
 change-custom-inventory-item-quantity)

(defn remove-inventory-item [character [_ selection-key item-key]]
  (update-in
   character
   [::entity/options selection-key]
   (fn [items] (vec (remove #(= item-key (::entity/key %)) items)))))

(reg-event-db
 :remove-inventory-item
 character-interceptors
 remove-inventory-item)

(defn remove-custom-inventory-item [character [_ custom-equipment-key name]]
  (update-in
   character
   [::entity/values custom-equipment-key]
   (fn [items]
     (vec (remove #(= name (:name %)) items)))))

(reg-event-db
 :remove-custom-inventory-item
 character-interceptors
 remove-custom-inventory-item)

(defn set-abilities [character [_ abilities]]
  (assoc-in character [::entity/options :ability-scores ::entity/value] abilities))

(reg-event-db
 :set-abilities
 character-interceptors
 set-abilities)

(defn swap-ability-values [character [_ i other-i k v]]
  (update-in
   character
   [::entity/options :ability-scores ::entity/value]
   (fn [a]
     (let [a-vec (vec a)
           other-index (mod other-i (count a-vec))
           [other-k other-v] (a-vec other-index)]
       (assoc a k other-v other-k v)))))

(reg-event-db
 :swap-ability-values
 character-interceptors
 swap-ability-values)

(defn decrease-ability-value [character [_ full-path k]]
  (update-in
   character
   full-path
   (fn [incs]
     (common/remove-first
      (fn [{inc-key ::entity/key}]
        (= inc-key k))
      incs))))

(reg-event-db
 :decrease-ability-value
 character-interceptors
 decrease-ability-value)

(defn increase-ability-value [character [_ full-path k]]
  (update-in
   character
   full-path
   conj
   {::entity/key k}))

(reg-event-db
 :increase-ability-value
 character-interceptors
 increase-ability-value)

(defn set-ability-score [character [_ ability-kw v]]
  (assoc-in character [::entity/options :ability-scores ::entity/value ability-kw] v))

(reg-event-db
 :set-ability-score
 character-interceptors
 set-ability-score)

(defn set-ability-score-variant [character [_ variant-key]]
  (assoc-in character [::entity/options :ability-scores ::entity/key] variant-key))

(reg-event-db
 :set-ability-score-variant
 character-interceptors
 set-ability-score-variant)

(defn select-skill [character [_ path selected? skill-key]]
  (update-in
   character
   path
   (fn [skills]
     (if selected?                                             
       (vec (remove (fn [s] (= skill-key (::entity/key s))) skills))
       (vec (conj skills {::entity/key skill-key}))))))

(reg-event-db
 :select-skill
 character-interceptors
 select-skill)

(defn set-total-hps [character [_ full-path first-selection selection average-value remainder]]
  (assoc-in
   character
   full-path
   {::entity/key :manual-entry
    ::entity/value (if (= first-selection selection)
                     (+ average-value remainder)
                     average-value)}))

(reg-event-db
 :set-total-hps
 character-interceptors
 set-total-hps)

(defn random-hit-points-option [levels class-kw]
  {::entity/key :roll
   ::entity/value (dice/die-roll (-> levels class-kw :hit-die))})

(defn randomize-hit-points [character [_ built-template path levels class-kw]]
  (assoc-in
   character
   (entity/get-entity-path built-template character path)
   (random-hit-points-option levels class-kw)))

(reg-event-db
 :randomize-hit-points
 character-interceptors
 randomize-hit-points)

(defn set-hit-points-to-average [character [_ built-template path levels class-kw]]
  (assoc-in
   character
   (entity/get-entity-path built-template character path)
   {::entity/key :average
    ::entity/value (dice/die-mean (-> levels class-kw :hit-die))}))

(reg-event-db
 :set-hit-points-to-average
 character-interceptors
 set-hit-points-to-average)

(defn set-level-hit-points [character [_ built-template character level-value value]]
  (assoc-in
   character
   (entity/get-entity-path built-template character (:path level-value))
   {::entity/key :manual-entry
    ::entity/value (if (not (js/isNaN value)) value)}))

(reg-event-db
 :set-level-hit-points
 character-interceptors
 set-level-hit-points)

(defn set-page [db [_ page-index]]
  (assoc db :page page-index))

(reg-event-db
 :set-page
 set-page)

(reg-event-fx
 :route
 (fn [{:keys [db]} [_ new-route]]
   (prn "ROUTE" (:route db) new-route)
   (let [{:keys [route route-history]} db]
     {:db (assoc db
                 :route new-route
                 :route-history (conj route-history route))
      :path (routes/path-for new-route)})))

(reg-event-db
 :set-user-data
 (fn [db [_ user-data]]
   (assoc db :user-data user-data)))

(defn set-active-tabs [db [_ active-tabs]]
  (assoc-in db tab-path active-tabs))

(reg-event-db
 :set-active-tabs
 set-active-tabs)

(defn set-loading [db [_ v]]
  (assoc db :loading v))

(reg-event-db
 :set-loading
 set-loading)

(reg-event-db
 :toggle-locked
 (fn [db [_ path]]
   (update db :locked-components (fn [comps]
                                   (if (comps path)
                                     (disj comps path)
                                     (conj comps path))))))

(reg-event-db
 :failed-loading-image
 character-interceptors
 (fn [character [_ image-url]]
   (update character
           ::entity/values
           assoc
           :image-url-failed
           image-url)))

(reg-event-db
 :failed-loading-faction-image
 character-interceptors
 (fn [character [_ faction-image-url]]
   (update character
           ::entity/values
           assoc
           :faction-image-url-failed
           faction-image-url)))

(reg-event-db
 :loaded-image
 character-interceptors
 (fn [character []]
   (update character
           ::entity/values
           dissoc
           :image-url-failed)))

(reg-event-db
 :loaded-faction-image
 character-interceptors
 (fn [character []]
   (update character
           ::entity/values
           dissoc
           :faction-image-url-failed)))

(reg-fx
 :http
 (fn [{:keys [on-success on-failure] :as cfg}]
   (go (let [response (<! (http/request cfg))]
         (prn "RESPONSE" response)
         (if (<= 200 (:status response) 299)
           (dispatch (conj on-success response))
           (dispatch (conj on-failure response)))))))

(reg-fx
 :path
 (fn [path]
   (.pushState js/window.history {} nil path)))

(defn backend-url [path]
  (if (s/starts-with? js/window.location.href "http://localhost")
    (str "http://localhost:8890" (if (not (s/starts-with? path "/")) "/") path)
    path))

(def login-url (backend-url "/login"))

(reg-event-db
 :login-success
 (fn [db [_ backtrack? response]]
   (cond-> db
       true (assoc :user-data (-> response :body))
       backtrack? (assoc :route (peek (:route-history db))))))

(reg-event-fx
 :login-failure
 (fn [{:keys [db]} [_ response]]
   (let [error-code (-> response :body :error)]
     (prn "ERROR CODE" error-code)
     (cond
       (= error-code errors/bad-credentials) {:dispatch [:set-user-data nil]}
       (= error-code errors/unverified) {:db (assoc db :temp-email (-> response :body :email))
                                         :dispatch [:route routes/verify-sent-route]}
       (= error-code errors/unverified-expired) {:dispatch [:route routes/verify-failed-route]}
       :else (prn "JUANCHO")))))

(reg-event-fx
 :logout
 (fn [cofx [_ response]]
   {:dispatch [:set-user-data nil]}))

(reg-event-fx
 :login
 (fn [cofx [_ params backtrack?]]
   {:http {:method :post
           :url login-url
           :json-params params
           :on-success [:login-success backtrack?]
           :on-failure [:login-failure]}}))

(reg-event-db
 :register-success
 (fn [db [_ backtrack? response]]
   (prn "SUCCESS RESPONSE" response)
   (assoc db
          :user-data (:body response)
          :route :verify-sent)))

(reg-event-fx
 :register-failure
 (fn [cofx [_ response]]
   (prn "FAILURE RESPONSE" response)
   {:dispatch [:set-user-data nil]}))

(reg-event-db
 :re-verify-success
 (fn [db []]
   (assoc db :route (-> (bidi/match-route routes/routes routes/verify-sent-route) :handler first))))

(defn validate-registration [])

(reg-event-db
 :email-taken
 (fn [db [_ response]]
   (prn "CHECK EMAIL RESPONSE" response)
   (assoc db :email-taken? (-> response :body (= "true")))))

(reg-event-db
 :username-taken
 (fn [db [_ response]]
   (prn "CHECK EMAIL RESPONSE" response)
   (assoc db :username-taken? (-> response :body (= "true")))))

(reg-event-db
 :registration-first-and-last-name
 (fn [db [_ first-and-last-name]]
   (assoc-in db [:registration-form :first-and-last-name] first-and-last-name)))

(reg-event-fx
 :registration-email
 (fn [{:keys [db]} [_ email]]
   {:db (assoc-in db [:registration-form :email] email)
    :dispatch [:check-email email]}))

(reg-event-fx
 :registration-username
 (fn [{:keys [db]} [_ username]]
   {:db (assoc-in db [:registration-form :username] username)
    :dispatch [:check-username username]}))

(reg-event-db
 :registration-password
 (fn [db [_ password]]
   (assoc-in db [:registration-form :password] password)))

(reg-event-db
 :registration-send-updates?
 (fn [db [_ send-updates?]]
   (assoc-in db [:registration-form :send-updates?] send-updates?)))

(reg-event-db
 :register-first-and-last-name
 (fn [db [_ first-and-last-name]]
   (assoc-in db [:registration-form :first-and-last-name] first-and-last-name)))

(reg-event-fx
 :check-email
 (fn [{:keys [db]} [_ email]]
   {:http {:method :get
           :url (backend-url (bidi/path-for routes/routes routes/check-email-route))
           :query-params {:email email}
           :on-success [:email-taken]}}))

(reg-event-fx
 :check-username
 (fn [{:keys [db]} [_ username]]
   (prn "USERNAME" username)
   {:http {:method :get
           :url (backend-url (bidi/path-for routes/routes routes/check-username-route))
           :query-params {:username username}
           :on-success [:username-taken]}}))

(reg-event-fx
 :register
 (fn [{:keys [db]} [_ params backtrack?]]
   (let [registration-form (:registration-form db)]
     {:db (assoc db :temp-email (:email registration-form))
      :http {:method :post
             :url (backend-url (bidi/path-for routes/routes routes/register-route))
             :json-params registration-form
             :on-success [:register-success backtrack?]
             :on-failure [:register-failure]}})))

(reg-event-fx
 :re-verify
 (fn [{:keys [db]} [_ params]]
   {:db (assoc db :temp-email (:email params))
    :http {:method :get
           :url (backend-url (bidi/path-for routes/routes routes/re-verify-route))
           :query-params params
           :on-success [:re-verify-success]
           :on-failure [:re-verify-failure]}}))
