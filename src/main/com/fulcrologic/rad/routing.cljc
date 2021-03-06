(ns com.fulcrologic.rad.routing
  "A support layer for application-level routing. RAD supports the idea of an *application-level* history. This
  allows it to abstract over the concepts of relative navigation since it can be used
  on many platforms (like React Native or Electron) where no natural browser history API exists.

  History support in RAD requires that you install an implementation of RouteHistory at application start time. See
  `com.fulcrologic.rad.routing.history` and associated namespaces.

  Functions in this namespace that do relative routing will silently fail if no such history support is installed."
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.routing.history :as history]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app]))

(defn absolute-path
  "Get the absolute path for the given route target."
  [app-ish RouteTarget route-params]
  (let [app      (comp/any->app app-ish)
        app-root (app/root-class app)]
    (dr/resolve-path app-root RouteTarget route-params)))

(defn route-to!
  "Change the UI to display the route to the specified class, with the additional parameter map as route params. If
  route history is installed, then it will be notified of the change."
  [app-or-component RADClass route-params]
  (if-let [path (absolute-path app-or-component RADClass route-params)]
    (do
      (when-not (every? string? path)
        (log/warn "Insufficient route parameters passed. Resulting route is probably invalid."
          (comp/component-name RADClass) route-params))
      (history/push-route! app-or-component path route-params)
      (dr/change-route! app-or-component path route-params))
    (log/error "Cannot find path for" (comp/component-name RADClass))))

(defn back!
  "Attempt to navigate back to the last point in history. Returns true if there is history support, false if
   it is impossible to even try to go back."
  [app-or-component]
  (if (history/history-support? app-or-component)
    (do
      (history/back! app-or-component)
      true)
    false))

(defn update-route-params!
  "Like `clojure.core/update`.

  Run `(apply f current-route-params args)` and store those as the current route params."
  [app-or-component f & args]
  (when (history/history-support? app-or-component)
    (let [{:keys [route params]} (history/current-route app-or-component)
          new-params (apply f params args)]
      (history/replace-route! app-or-component route new-params))))
