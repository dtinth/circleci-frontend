(ns frontend.components.app
  (:require [compassus.core :as compassus]
            [frontend.async :refer [raise!]]
            [frontend.components.app.legacy :as legacy]
            [frontend.components.aside :as aside]
            [frontend.components.common :as common]
            [frontend.components.enterprise-landing :as enterprise-landing]
            [frontend.components.inspector :as inspector]
            [frontend.components.instrumentation :as instrumentation]
            [frontend.components.pages.projects :as projects]
            [frontend.components.pages.run :as run]
            [frontend.components.pages.user-settings :as user-settings]
            [frontend.components.pages.workflow :as workflow]
            [frontend.components.pieces.flash-notification :as flash]
            [frontend.components.pieces.topbar :as topbar]
            [frontend.components.statuspage :as statuspage]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.utils :as utils :refer-macros [html]]
            [frontend.utils.launchdarkly :as ld]
            [frontend.utils.legacy :refer [build-legacy]]
            [om.core :as om :include-macros true]
            [om.next :as om-next :refer-macros [defui]]))

(defui ^:once Loading
  Object
  (render [this] nil))

(def routes
  {:route/loading Loading
   :route/legacy-page legacy/LegacyPage
   :route/projects projects/Page
   :route/account user-settings/Page
   :route/workflow workflow/Page
   :route/run run/Page})

(def index-route :route/loading)

(defn head-admin [app owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Header")
    om/IRender
    (render [_]
      (let [open? (get-in app state/show-admin-panel-path)
            expanded? (get-in app state/show-instrumentation-line-items-path)
            inspector? (get-in app state/show-inspector-path)
            user-session-settings (get-in app [:render-context :user_session_settings])
            env (config/env)]
        (html
         [:div
          [:div.environment {:class (str "env-" env)
                             :role "button"
                             :on-click #(raise! owner [:show-admin-panel-toggled])}
           env]
          [:div.head-admin {:class (concat (when open? ["open"])
                                           (when expanded? ["expanded"]))}
           [:div.admin-tools


            [:div.options
             [:a {:href "/admin/switch"} "switch "]
             (let [use-local-assets (get user-session-settings :use_local_assets)]
               [:a {:on-click #(raise! owner [:set-user-session-setting {:setting :use-local-assets
                                                                         :value (not use-local-assets)}])}
                "local assets " (if use-local-assets "off " "on ")])
             (let [current-build-id (get user-session-settings :om_build_id "dev")]
               (for [build-id (remove (partial = current-build-id) ["dev" "whitespace" "production"])]
                 [:a.menu-item
                  {:key build-id
                   :on-click #(raise! owner [:set-user-session-setting {:setting :om-build-id
                                                                        :value build-id}])}
                  [:span (str "om " build-id " ")]]))
             [:a {:on-click #(raise! owner [:show-inspector-toggled])}
              (if inspector? "inspector off " "inspector on ")]
             [:a {:on-click #(raise! owner [:clear-instrumentation-data-clicked])} "clear stats"]]
            (om/build instrumentation/summary (:instrumentation app))]
           (when (and open? expanded?)
             (om/build instrumentation/line-items (:instrumentation app)))]])))))

(defn blocked-page [app]
  (let [reason (get-in app [:enterprise :site-status :blocked_reason])]
    (html
     [:div.outer {:style {:padding-top "0"}}
      [:div.enterprise-landing
       [:div.jumbotron
        common/language-background-jumbotron
        [:section.container
         [:div.row
          [:article.hero-title.center-block
           [:div.text-center (enterprise-landing/enterprise-logo)]
           [:h1.text-center "Error Launching CircleCI"]]]]
        [:div.row.text-center
         [:h2 reason]]]
       [:div.outer-section]]])))

(defn app-blocked? [app]
  (and (config/enterprise?)
       (= "blocked" (get-in app [:enterprise :site-status :status]))))

(defui ^:once Wrapper
  static om-next/IQuery
  (query [this]
    '[{:legacy/state [*]}])
  Object
  (render [this]
    (let [app (:legacy/state (om-next/props this))
          {:keys [factory props owner]} (om-next/get-computed this)]
      (if (app-blocked? app)
        (blocked-page app)
        (let [user (get-in app state/user-path)
              admin? (if (config/enterprise?)
                       (get-in app [:current-user :dev-admin])
                       (get-in app [:current-user :admin]))
              show-inspector? (get-in app state/show-inspector-path)
              ;; :landing is still used by Enterprise. It and :error are
              ;; still "outer" pages.
              outer? (contains? #{:landing :error} (:navigation-point app))]
          (html
           [:div {:class (if outer? "outer" "inner")
                  :on-click utils/disable-natural-form-submission}
            (when admin?
              (build-legacy head-admin app))

            (when show-inspector?
              (inspector/inspector))

            (when (config/statuspage-header-enabled?)
              (build-legacy statuspage/statuspage app))

            [:.top
             [:.bar
              (when (ld/feature-on? "top-bar-ui-v-1")
                (build-legacy topbar/topbar {:user user}))]
             [:.flash-presenter
              (flash/presenter {:display-timeout 2000
                                :notification
                                (when-let [{:keys [number message]} (get-in app state/flash-notification-path)]
                                  (flash/flash-notification {:react-key number} message))})]]

            [:.below-top
             (when (and (not outer?) user)
               (let [compassus-route (compassus/current-route owner)
                     current-route (if (= :route/legacy-page compassus-route)
                                     (:navigation-point app)
                                     compassus-route)]
                 (build-legacy aside/aside-nav {:user user :current-route current-route})))

             (factory props)]]))))))
