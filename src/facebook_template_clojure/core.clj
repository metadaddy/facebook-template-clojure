(ns facebook-template-clojure.core
  (:use [clojure.data.json :only [read-json]] 
        [compojure.core :only [defroutes GET POST]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.params :only [wrap-params]]
        [ring.util.response :only [redirect]]
        [ring.middleware.stacktrace :only [wrap-stacktrace-web]]
        [ring.handler.dump :only [handle-dump]]
        [clj-facebook-graph.auth :only [with-facebook-auth with-facebook-access-token make-auth-request *facebook-auth*]]
        [clj-facebook-graph.helper :only [facebook-base-url]]
        [clj-facebook-graph.ring-middleware :only [wrap-facebook-access-token-required
                                                   wrap-facebook-extract-callback-code
                                                   wrap-facebook-auth]])
  (:require [ring.adapter.jetty :as jetty]
            [compojure.route :as route]
            [net.cgrand.enlive-html :as enlive]
            [clj-http.client :as http-client]
            [clj-facebook-graph.helper :as fb-helper]
            [clj-facebook-graph.client :as fb-client])
  (:import [java.lang Exception]
           [clj_facebook_graph FacebookGraphException]))

(defonce facebook-app-info {:client-id (System/getenv "FACEBOOK_APP_ID")
                            :client-secret (System/getenv "FACEBOOK_SECRET")
                            :redirect-uri (System/getenv "REDIRECT_URI")
                            :scope  ["user_likes" "user_photos" "user_photo_video_tags"]})

(enlive/defsnippet myfriends-model "templates/index.html" [:ul#myfriends :> enlive/first-child]
  [{name :name id :id}]
  [:a] (enlive/set-attr :onclick (str "window.open('http://www.facebook.com/" id "')"))
  [:a :> :img] (enlive/do->
          (enlive/set-attr :alt name)
          (enlive/set-attr :src (str "https://graph.facebook.com/" id "/picture?type=square"))
          (enlive/after name)))

(enlive/defsnippet likes-model "templates/index.html" [:ul.things :> enlive/first-child]
  [{name :name id :id}]
  [:a] (enlive/set-attr :onclick (str "window.open('http://www.facebook.com/" id "')"))
  [:a :> :img] (enlive/do->
          (enlive/set-attr :alt name)
          (enlive/set-attr :src (str "https://graph.facebook.com/" id "/picture?type=square"))
          (enlive/after name)))

(enlive/defsnippet photos-model "templates/index.html" [:ul.photos :> enlive/first-child]
  [{picture :picture name :name id :id}]
  [:li] (enlive/set-attr :style (str "background-image: url(" picture ")"))
  [:li :> :a] (enlive/do->
          (enlive/set-attr :onclick (str "window.open('http://www.facebook.com/" id "')"))
          (enlive/content name)))

(enlive/defsnippet appfriends-model "templates/index.html" [:ul#appfriends :> enlive/first-child]
  [{pic_square :pic_square name :name uid :uid}]
  [:a] (enlive/set-attr :onclick (str "window.open('http://www.facebook.com/" uid "')"))
  [:a :> :img] (enlive/do->
          (enlive/set-attr :alt name)
          (enlive/set-attr :src pic_square)
          (enlive/after name)))

(enlive/deftemplate index "templates/index.html"
    [host app & [user friends photos likes appfriends]]
    [:title] (enlive/content (:name app))
    [(and 
      (enlive/has [:meta]) 
      (enlive/attr-has :property "og:url"))] (enlive/set-attr :content (str "https://" host "/"))
    [(and 
      (enlive/has [:meta]) 
      (enlive/attr-has :property "og:image"))] (enlive/set-attr :content (str "https://" host "/logo.png"))
    [(and 
      (enlive/has [:meta]) 
      (enlive/attr-has :property "og:title"))] (enlive/set-attr :content (:name app))
    [(and 
      (enlive/has [:meta]) 
      (enlive/attr-has :property "og:site_name"))] (enlive/set-attr :content (:name app))
    [(and 
      (enlive/has [:meta]) 
      (enlive/attr-has :property "fb:app_id"))] (enlive/set-attr :content (:id app))
    [:div.no-user (enlive/pred (fn [node] (not= user nil)))] nil
    [:div.user-logged-in (enlive/pred (fn [node] (= user nil)))] nil
    [:strong#username] (enlive/content (:name user))
    [:p#picture] (enlive/set-attr :style (str "background-image: url(https://graph.facebook.com/" (:id user) "/picture?type=normal)"))
    [:a#postToWall] (enlive/set-attr :data-url (str "https://" host "/"))
    [:a#sendToFriends] (enlive/set-attr :data-url (str "https://" host "/"))
    [:a#applink] (enlive/do->
                    (enlive/content (:name app))
                    (enlive/set-attr :href (:link app)))
    [:ul#myfriends] (enlive/content (map myfriends-model friends))
    [:ul.photos] (enlive/content (map photos-model photos))
    [:ul.photos :> (enlive/nth-child 4 1)] (enlive/add-class "first-column")
    [:ul#appfriends] (enlive/content (map appfriends-model appfriends))
    [:ul.things] (enlive/content (map likes-model likes)))

(defn render 
    "Helper function for rendering Enlive output"
    [t] (apply str t))

;; Can get application data from Facebook without an access token
(defn app []
  (read-json 
    (:body 
      (http-client/get 
        (str fb-helper/facebook-base-url "/" (System/getenv "FACEBOOK_APP_ID"))))))

(defroutes handler
  (GET "/" {headers :headers} 
       (if (not *facebook-auth*)
         (render (index (get headers "host") (app)))
         (let [user (fb-client/get [:me] {:extract :body})
               friends (fb-client/get [:me :friends] {:query-params {:limit 4} :extract :data})
               photos (fb-client/get [:me :photos] {:query-params {:limit 16} :extract :data})
               likes (fb-client/get [:me :likes] {:query-params {:limit 4} :extract :data})
               appfriends (:body (fb-client/get :fql {:fql "SELECT uid, name, is_app_user, pic_square FROM user WHERE uid in (SELECT uid2 FROM friend WHERE uid1 = me()) AND is_app_user = 1"}))]
              (render (index (get headers "host") (app) user friends photos likes appfriends)))))
  (GET "/auth/facebook" [] 
    (if (not *facebook-auth*)
      (throw
       (FacebookGraphException.
        {:error :facebook-login-required}))
      (redirect "/")))
  (GET "/show-session" {session :session} (str session))
  (route/files "/" {:root "www/public"})
  (route/not-found "Page not found"))

(def app
  (-> handler
      (wrap-facebook-auth facebook-app-info "/facebook-login")
      (wrap-facebook-extract-callback-code facebook-app-info handle-dump)
      (wrap-facebook-access-token-required facebook-app-info)
      wrap-session
      wrap-params
      wrap-stacktrace-web))

(defn -main []
  (let [port (Integer/parseInt (get (System/getenv) "PORT" "8080"))]
    (jetty/run-jetty app {:port port})))
