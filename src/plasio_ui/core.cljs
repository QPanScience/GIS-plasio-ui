(ns ^:figwheel-always plasio-ui.core
    (:require [plasio-ui.widgets :as w]
              [plasio-ui.math :as math]
              [plasio-ui.history :as history]
              [reagent.core :as reagent :refer [atom]]
              [cljs.core.async :as async]
              [cljs-http.client :as http]
              cljsjs.gl-matrix)
    (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

;; define your app data so that it doesn't get over-written on reload

(defonce app-state (atom {:left-hud-collapsed? false
                          :right-hud-collapsed? false
                          :ro {:point-size 2
                               :point-size-attenuation 1
                               :intensity-blend 0
                               :intensity-clamps [0 255]}
                          :po {:distance-hint 50
                               :max-depth-reduction-hint 5}}))

;; when this value is true, everytime the app-state atom updates, a snapshot is
;; requested (history) when this is set to false, you may update the app-state
;; without causing a snapshot however the UI  state will still update
(def ^:dynamic ^:private *save-snapshot-on-ui-update* true)

;; Much code duplication here, but I don't want to over engineer this
;;
(defn hud-left [& children]
  (let [is-collapsed? (:left-hud-collapsed? @app-state)]
    [:div.hud-container.hud-left
     {:class (when is-collapsed? " hud-collapsed")}
     [:a.hud-collapse {:href     "javascript:"
                       :on-click #(swap! app-state update-in [:left-hud-collapsed?] not)}
      (if is-collapsed? "\u00BB" "\u00AB")]
     (into [:div.hud-contents] children)]))


(defn hud-right [& children]
  (let [is-collapsed? (:right-hud-collapsed? @app-state)]
    [:div.hud-container.hud-right
     {:class (when is-collapsed? " hud-collapsed")}
     [:a.hud-collapse {:href     "javascript:"
                       :on-click #(swap! app-state update-in [:right-hud-collapsed?] not)}
      (if is-collapsed? "\u00AB" "\u00BB")]
     (into [:div.hud-contents] children)]))



(defn compass []
  ;; we keep track of two angles, one is where we're looking and the second one
  ;; matches our tilt
  ;;
  (let [angles (atom [0 0])
        zvec   (array 0 0 -1)]
    (reagent/create-class
     {:component-did-mount
      (fn []
        (if-let [renderer (get-in @app-state [:comps :renderer])]
          (.addPropertyListener
           renderer (array "view")
           (fn [view]
             (when view
               (let [eye (.-eye view)
                     target (.-target view)]
                 ;; such calculations, mostly project vectors to xz plane and
                 ;; compute the angle between the two vectors
                 (when (and eye target)
                   (let [plane (math/target-plane target)       ;; plane at target
                         peye (math/project-point plane eye)    ;; project eye
                         v (math/make-vec target peye)          ;; vector from target to eye
                         theta (math/angle-between zvec v)      ;; angle between target->eye and z
                         theta (math/->deg theta)               ;; in degrees

                         t->e (math/make-vec target eye)        ;; target->eye vector
                         t->pe (math/make-vec target peye)      ;; target->projected eye vector
                         incline (math/angle-between t->e t->pe)  ;; angle between t->e and t->pe
                         incline (math/->deg incline)]            ;; in degrees

                     ;; make sure the values are appropriately adjusted for them to make sense as
                     ;; css transforms
                     (reset! angles
                             [(if (< (aget v 0) 0)
                                theta
                                (- 360 theta))
                              (- 90 (max 20 incline))])))))))
          (throw (js/Error. "Renderer is not intialized, cannot have compass if renderer is not available"))))
      :reagent-render
      (fn []
        (let [[heading incline] @angles
              camera (get-in @app-state [:comps :camera])
              te (get-in @app-state [:comps :target-element])]
          [:a.compass {:style {:transform (str "rotateX(" incline "deg)")}
                       :href "javascript:"
                       :on-click #(do (when camera
                                        (.setHeading camera 0)))}
           [:div.arrow {:style {:transform (str "rotateZ(" heading "deg)")}}
            [:div.n]
            [:div.s]]
           [:div.circle]]))})))

(declare initialize-for-pipeline)

(defn- camera-state [cam]
  {:azimuth (.. cam -azimuth)
   :distance (.. cam -distance)
   :max-distance (.. cam -maxDistance)
   :target (into [] (.. cam -target))
   :elevation (.. cam -elevation)})

(defn- js-camera-props [{:keys [azimuth distance max-distance target elevation]}]
  (js-obj
   "azimuth" azimuth
   "distance" distance
   "maxDistance" max-distance
   "target" (apply array target)
   "elevation" elevation))

(defn- ui-state [st]
  (select-keys st [:ro :po]))

(defn- params-state [st]
  (select-keys st [:server :pipeline]))

(defn- apply-state!
  "Given a state snapshot, apply it"
  [params]
  ;; apply camera state if any
  (when-let [cp (:camera params)]
    (let [camera (get-in @app-state [:comps :camera])
          cam-props (js-camera-props cp)]
      (println cam-props)
      (.applyState camera cam-props)))

  ;; apply UI state if any
  (binding [*save-snapshot-on-ui-update* false]
    (swap! app-state merge (select-keys params [:ro :po]))))

(defn- save-current-snapshot!
  "Take a snapshot from the camera and save it"
  []
  (if-let [camera (get-in @app-state [:comps :camera])]
    (history/push-state
     (merge
      {:camera (camera-state camera)}
      (ui-state @app-state)
      (params-state @app-state)))))

;; A simple way to throttle down changes to history, waits for 500ms
;; before applying a state, gives UI a chance to "settle down"
;;
(let [current-index (atom 0)]
  (defn do-save-current-snapshot []
    (go
      (let [index (swap! current-index inc)]
        (async/<! (async/timeout 500))
        (when (= index @current-index)
          ;; the index hasn't changed since we were queued for save
          (save-current-snapshot!))))))


(defn apply-ui-state!
  ([n]
   (let [r (get-in n [:comps :renderer])
         ro (:ro n)
         p (get-in n [:comps :policy])]
     (.setRenderOptions r (js-obj
                           "pointSize" (:point-size ro)
                           "pointSizeAttenuation"
                             (array 1 (:point-size-attenuation ro))
                           "intensityBlend" (:intensity-blend ro)
                           "clampLower" (nth (:intensity-clamps ro) 0)
                           "clampHigher" (nth (:intensity-clamps ro) 1)))
     (doto p
       (.setDistanceHint (get-in n [:po :distance-hint]))
       (.setMaxDepthReductionHint (->> (get-in n [:po :max-depth-reduction-hint])
                                       (- 5)
                                       js/Math.floor))))))


(defn render-target []
  (let [this (reagent/current-component)]
    (reagent/create-class
     {:component-did-mount
      (fn []
        (let [init-state (history/current-state-from-query-string)
              comps (initialize-for-pipeline (reagent/dom-node this)
                                             {:server     (:server @app-state)
                                              :pipeline   (:pipeline @app-state)
                                              :max-depth  (:max-depth @app-state)
                                              :compress?  true
                                              :bbox       (:bounds @app-state)
                                              :color?     (:color? @app-state)
                                              :intensity? (:intensity? @app-state)
                                              :ro         (:ro @app-state)
                                              :init-params init-state})]
          (swap! app-state assoc :comps comps))

        ;; listen to changes to history
        (history/listen (fn [st]
                          (println "apply" st)
                          (apply-state! st))))

      :reagent-render
      (fn []
        [:div#render-target])})))


(defn hud []
  (reagent/create-class
   {:component-did-mount
    (fn []
      ;; subscribe to state changes, so that we can trigger appropriate render
      ;; options
      (add-watch app-state "__render-applicator"
                 (fn [_ _ o n]
                   (apply-ui-state! n)
                   (when *save-snapshot-on-ui-update*
                     (do-save-current-snapshot)))))

    :reagent-render
    (fn []
      ;; get the left and right hud's up
      ;; we need these to place our controls and other fancy things
      ;;
      [:div.container
       ;; This is going to be where we render stuff
       [render-target]

       ;; hud elements
       (hud-left
        ;; show app brand
        [:div#brand (or (:brand @app-state)
                        "Plasio-UI")
         [:div#sub-brand (or (:sub-brand @app-state)
                             "Dynamic Point Cloud Renderer")]]

        ;; Point appearance
        [w/panel "Point Rendering"

         ;; base point size
         [w/panel-section
          [w/desc "Base point size"]
          [w/slider (get-in @app-state [:ro :point-size]) 1 10
           #(swap! app-state assoc-in [:ro :point-size] %)]]

         ;; point attenuation factor
         [w/panel-section
          [w/desc "Attenuation factor, points closer to you are bloated more"]
          [w/slider (get-in @app-state [:ro :point-size-attenuation]) 0 5
           #(swap! app-state assoc-in [:ro :point-size-attenuation] %)]]

         ;; intensity blending factor
         [w/panel-section
          [w/desc "Intensity blending, how much of intensity to blend with color"]
          [w/slider (get-in @app-state [:ro :intensity-blend]) 0 1
           #(swap! app-state assoc-in [:ro :intensity-blend] %)]]

         ;; intensity scaling clamp
         [w/panel-section
          [w/desc "Intensity scaling, narrow down range of intensity values"]
          [w/slider (get-in @app-state [:ro :intensity-clamps]) 0 255
           #(swap! app-state assoc-in [:ro :intensity-clamps] (vec (seq %)))]]]

        ;; split plane distance
        [w/panel "Point Loading"

         ;; How close the first splitting plane is
         [w/panel-section
          [w/desc "Distance for highest resolution data.  Farther it is, more points get loaded."]
          [w/slider (get-in @app-state [:po :distance-hint]) 10 70
           #(swap! app-state assoc-in [:po :distance-hint] %)]]

         [w/panel-section
          [w/desc "Maximum resolution reduction.  Lower values means you see more of the lower density points."]
          [w/slider (get-in @app-state [:po :max-depth-reduction-hint]) 0 5
           #(swap! app-state assoc-in [:po :max-depth-reduction-hint] %)]]])

       [compass]


       #_(hud-right
          (w/panel "Many Descriptions"
                   [:div "Hi"]))])}))

(defn initialize-for-pipeline [e {:keys [server pipeline max-depth
                                         compress? color? intensity? bbox ro
                                         init-params]}]
  (let [create-renderer (.. js/window -renderer -core -createRenderer)
        renderer (create-renderer e)
        loaders (merge
                 {:point     (js/PlasioLib.Loaders.GreyhoundPipelineLoader. server pipeline max-depth compress? color? intensity?)
                  :transform (js/PlasioLib.Loaders.TransformLoader.)}
                 (when (not color?)
                   {:overlay (js/PlasioLib.Loaders.MapboxLoader.)}))
        policy (js/PlasioLib.FrustumLODNodePolicy. (clj->js loaders) renderer (apply js/Array bbox) nil max-depth)
        camera (js/PlasioLib.Cameras.Orbital.
                e renderer
                (fn [eye target final? applying-state?]
                  ;; when the state is final and we're not applying a state, make a history
                  ;; record of this
                  ;;
                  (when (and final?
                             (not applying-state?))
                    (do-save-current-snapshot))

                  ;; go ahead and update the renderer
                  (doto renderer
                    (.setEyePosition eye)
                    (.setTargetPosition target)))
                ;; if there are any init-params to the camera, specify them here
                ;;
                (when (-> init-params :camera seq)
                  (println (:camera init-params))
                  (js-camera-props (:camera init-params))))]

    ;; add loaders to our renderer, the loader wants the actual classes and not the instances, so we use
    ;; Class.constructor here to add loaders, more like static functions in C++ classes, we want these functions
    ;; to depend on absolutely no instance state
    ;;
    (doseq [[type loader] loaders]
      (js/console.log loader)
      (.addLoader renderer (.-constructor loader)))

    ;; attach a resize handler
    (let [handle-resize (fn []
                          (let [w (.. js/window -innerWidth)
                                h (.. js/window -innerHeight)]
                            (.setRenderViewSize renderer w h)))]
      (set! (. js/window -onresize) handle-resize)
      (handle-resize))

    ;; listen to some properties
    (doto policy
      (.on "bbox"
           (fn [bb]
             (let [bn (.. bb -mins)
                   bx (.. bb -maxs)
                   x  (- (aget bx 0) (aget bn 0))
                   y  (- (aget bx 1) (aget bn 1))
                   z  (- (aget bx 2) (aget bn 2))
                   far-dist (* 2 (js/Math.sqrt (* x x) (* y y)))]

               ;; only set hints for distance etc. when no camera init parameters were specified
               (when-not (:camera init-params)
                 (.setHint camera (js/Array x y z)))

               (.updateCamera renderer 0 (js-obj "far" far-dist))))))

    ;; set some default render state
    ;;
    (.setRenderOptions renderer
                       (js-obj "circularPoints" 1
                               "overlay_f" (if color? 0 1)
                               "rgb_f" (if color? 1 0)
                               "intensity_f" 1
                               "clampLower" (nth (:intensity-clamps ro) 0)
                               "clampHigher" (nth (:intensity-clamps ro) 1)
                               "maxColorComponent" 255
                               "pointSize" (:point-size ro)
                               "pointSizeAttenuation" (array 1 (:point-size-attenuation ro))
                               "intensityBlend" (:intensity-blend ro)))
    (.setClearColor renderer 0.1 0 0)

    (.start policy)

    (when-let [dh (get-in init-params [:po :distance-hint])]
      (.setDistanceHint policy dh))

    (when-let [pmdr (get-in init-params [:po :max-depth-reduction-hint])]
      (.setMaxDepthReductionHint policy (js/Math.floor (- 5 pmdr))))

    ;; also make sure that initial state is applied
    ;;
    {:renderer renderer
     :target-element e
     :camera camera
     :policy policy}))

(defn- urlify [s]
  (if (re-find #"https?://" s)
    s
    (str "http://" s)))


(defn pipeline-params [init-state]
  (go
    (let [server (:server init-state)
          pipeline (:pipeline init-state)

          base-url (-> (str server "/resource/" pipeline)
                       urlify)
          ;; get the bounds for the given pipeline
          ;;
          bounds (-> base-url
                     (str "/bounds")
                     (http/get {:with-credentials? false})
                     <!
                     :body)

          ;; if bounds are 4 in count, that means that we don't have z stuff
          ;; in which case we just give it a range
          bounds (if (= 4 (count bounds))
                   (apply conj (subvec bounds 0 2)
                          0
                          (conj (subvec bounds 2 4) 1000))
                   bounds)

          ;; get the total number of points
          num-points (-> base-url
                         (str "/numpoints")
                         (http/get {:with-credentials? false})
                         <!
                         :body)

          ;; fetch the native resource schema to figure out what dimensions we
          ;; are working with
          schema (-> base-url
                     (str "/schema")
                     (http/get {:with-credentials? false})
                     <!
                     :body)

          dim-names (set (mapv :name schema))
          colors '("Red" "Green" "Blue")]
      {:server (urlify server)
       :pipeline pipeline
       :bounds bounds
       :num-points num-points
       :intensity? (contains? dim-names "Intensity")
       :color? (every? true? (map #(contains? dim-names %) colors))
       :max-depth (-> num-points
                      js/Math.log
                      (/ (js/Math.log 4))
                      js/Math.ceil)})))

(defn startup []
  (go
    (let [defaults (-> "config.json"
                       (http/get {:with-credentials? false})
                       <!
                       :body)
          override (or (history/current-state-from-query-string) {})

          local-settings (merge defaults override)
          remote-settings (<! (pipeline-params local-settings))

          settings (merge local-settings remote-settings)

          hard-blend? (get-in settings [:ro :intensity-blend])
          color? (:color? settings)
          intensity? (:intensity? settings)]

      (println "color? " color?)
      (println "intensity? " intensity?)

      (swap! app-state merge settings)

      ;; if we don't yet have an intensity blend setting from the URL or
      ;; elsewhere, assign one based on whether we have color/intensity.
      (when (not hard-blend?)
        (swap! app-state assoc-in [:ro :intensity-blend]
               (if intensity? 0.2 0)))

      (println "Startup state: " @app-state))

    (reagent/render-component [hud]
                              (. js/document (getElementById "app")))))


(startup)

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)
