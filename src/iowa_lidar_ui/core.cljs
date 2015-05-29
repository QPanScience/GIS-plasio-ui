(ns ^:figwheel-always iowa-lidar-ui.core
    (:require [iowa-lidar-ui.widgets :as w]
              [iowa-lidar-ui.math :as math]
              [iowa-lidar-ui.history :as history]
              [reagent.core :as reagent :refer [atom]]
              cljsjs.gl-matrix))

(enable-console-print!)

;; define your app data so that it doesn't get over-written on reload

(defonce app-state (atom {:left-hud-collapsed? false
                          :right-hud-collapsed? false}))

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

(defn- apply-state!
  [{:keys [cd cmd ct ce ca] :as params}]
  ;; apply camera state if any
  (when (and ca ct ce cd cmd)
    (let [camera (get-in @app-state [:comps :camera])]
      (.applyState camera
                   (js-obj "distance" cd
                           "maxDistance" cmd
                           "target" (apply array ct)
                           "elevation" ce
                           "azimuth" ca)))))

(defn- current-state []
  (let [camera (get-in @app-state [:comps :camera])
        props (.serialize camera)]
    {:ca (.. props -azimuth)
     :cd (.. props -distance)
     :cmd (.. props -maxDistance)
     :ct (into [] (.. props -target))
     :ce (.. props -elevation)}))

(defn- save-current-snapshot! []
  (let [cst (current-state)]
    (history/push-state cst)))

(defn render-target []
  (let [this (reagent/current-component)]
    (reagent/create-class
      {:component-did-mount
       (fn []
         (let [comps (initialize-for-pipeline (reagent/dom-node this)
                                              {:server    "http://data.iowalidar.com"
                                               :pipeline  "ia-nineteen"
                                               :max-depth 19
                                               :compress? true
                                               :bbox      [-10796577.371225, 4902908.135781, 0,
                                                           -10015953.953824, 5375808.896799, 1000]
                                               :imagery?  true})]
           (swap! app-state assoc :comps comps))

         ;; make sure history stuff is taken care of, if there is a state in the URL
         ;; apply it, and be sure to apply states on navigation changes
         ;;
         (let [st (history/current-state-from-query-string)]
           (when (seq st)
             (apply-state! st)))

         (history/listen (fn [st]
                          (apply-state! st))))

       :reagent-render
       (fn []
         [:div#render-target])})))

(defn hud []
  ;; get the left and right hud's up
  ;; we need these to place our controls and other fancy things
  ;;
  [:div.container
   ;; This is going to be where we render stuff
   [render-target]

   ;; hud elements
   (hud-left
     ;; show app brand
     [:div#brand "Iowa-Lidar"
      [:div#sub-brand "Statewide Point Cloud Renderer"]]

     ;; Point size
     [w/panel "Point Rendering"

      ;; base point size
      [w/panel-section
       [w/desc "Base point size"]
       [w/slider 2 1 10
        (fn [val]
          (when-let [r (get-in @app-state [:comps :renderer])]
            (.setRenderOptions r (js-obj "pointSize" val))))]]

      ;; point attenuation factor
      [w/panel-section
       [w/desc "Attenuation factor, points closer to you are bloated more"]
       [w/slider 1 0 5
        (fn [val]
          (when-let [r (get-in @app-state [:comps :renderer])]
            (.setRenderOptions r (js-obj "pointSizeAttenuation" (array 1 val)))))]]]

     ;; split plane distance
     [w/panel "Point Loading"

      ;; How close the first splitting plane is
      [w/panel-section
       [w/desc "Distance for highest resolution data.  Farther it is, more points get loaded."]
       [w/slider 50 10 70
        (fn [val]
          (when-let [policy (get-in @app-state [:comps :policy])]
            (.setDistanceHint policy val)))]]

      [w/panel-section
       [w/desc "Maximum resolution reduction.  Lower values means you see more of the lower density points."]
       [w/slider 5 0 5
        (fn [val]
          (when-let [policy (get-in @app-state [:comps :policy])]
            (let [val (js/Math.floor (- 5 val))]
              (js/console.log policy)
              (.setMaxDepthReductionHint policy val))))]]])

   [compass]


   #_(hud-right
     (w/panel "Many Descriptions"
              [:div "Hi"]))])

(defn initialize-for-pipeline [e {:keys [server pipeline max-depth
                                         compress? color? intensity? bbox
                                         imagery?]}]
  (let [create-renderer (.. js/window -renderer -core -createRenderer)
        renderer (create-renderer e)
        loaders (merge
                  {:point     (js/PlasioLib.Loaders.GreyhoundPipelineLoader. server pipeline max-depth compress? color? intensity?)
                   :transform (js/PlasioLib.Loaders.TransformLoader.)}
                  (when imagery?
                    {:overlay (js/PlasioLib.Loaders.MapboxLoader.)}))
        policy (js/PlasioLib.FrustumLODNodePolicy. (clj->js loaders) renderer (apply js/Array bbox))
        camera (js/PlasioLib.Cameras.Orbital. e renderer
                                              (fn [eye target]
                                                (doto renderer
                                                  (.setEyePosition eye)
                                                  (.setTargetPosition target))))]

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
                            (println "resizing to:" w h)
                            (.setRenderViewSize renderer w h)))]
      (set! (. js/window -onresize) handle-resize)
      (handle-resize))

    ;; listen to some properties properties
    (doto policy
      (.on "bbox"
           (fn [bb]
             (let [bn (.. bb -mins)
                   bx (.. bb -maxs)
                   x  (- (aget bx 0) (aget bn 0))
                   y  (- (aget bx 1) (aget bn 1))
                   z  (- (aget bx 2) (aget bn 2))
                   far-dist (* 2 (js/Math.sqrt (* x x) (* y y)))]
               (print "setting hint:" x y z)
               (.setHint camera (js/Array x y z))
               (.updateCamera renderer 0 (js-obj "far" far-dist)))))

      (.on "view-changed"
           (fn []
             (save-current-snapshot!)
             (println "view-changed!"))))

    ;; set some default render state
    ;;
    (.setRenderOptions renderer
                     (js-obj "pointSize" 1
                             "circularPoints" 1
                             "overlay_f" 1))
    (.setClearColor renderer 0.1 0 0)

    (.start policy)

    {:renderer renderer
     :target-element e
     :camera camera
     :policy policy}))

(reagent/render-component [hud]
                          (. js/document (getElementById "app")))


(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
) 
