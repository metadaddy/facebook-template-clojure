(ns facebook-template-clojure.core
  (:use [clojure.pprint :only [pprint]]
        [clojure.data.json :only [read-json]] 
        [compojure.core :only [defroutes GET POST]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.params :only [wrap-params]]
        [ring.util.response :only [redirect]]
        [ring.middleware.stacktrace :only [wrap-stacktrace-web]]
        [ring.middleware.session.memory :only [memory-store]]
        [ring.handler.dump :only [handle-dump]]
        [clj-facebook-graph.auth :only [with-facebook-auth with-facebook-access-token make-auth-request *facebook-auth*]]
        [clj-facebook-graph.helper :only [facebook-base-url]]
        [clj-facebook-graph.ring-middleware :only [wrap-facebook-access-token-required
                                                   wrap-facebook-extract-callback-code
                                                   wrap-facebook-auth]]
        hiccup.core
        ring.adapter.jetty)
  (:require [ring.adapter.jetty :as jetty]
            [compojure.route :as route]
            [net.cgrand.enlive-html :as html]
            [clj-http.client :as http-client]
            [clj-facebook-graph.helper :as fb-helper]
            [clj-facebook-graph.client :as fb-client])
  (:import [java.lang Exception]
           [clj_facebook_graph FacebookGraphException]))

(defonce facebook-app-info {:client-id (System/getenv "FACEBOOK_APP_ID")
                            :client-secret (System/getenv "FACEBOOK_SECRET")
                            :redirect-uri "http://localhost:8080/facebook-callback" ;; TODO
                            :scope  ["user_photos" "friends_photos" "publish_stream"]})

(html/defsnippet myfriends-model "templates/index.html" [:ul#myfriends :> html/first-child]
  [{name :name id :id}]
  [:a] (html/set-attr :onclick (str "window.open('http://www.facebook.com/" id "')"))
  [:a :> :img] (html/do->
          (html/set-attr :alt name)
          (html/set-attr :src (str "https://graph.facebook.com/" id "/picture?type=square"))
          (html/after name)))

(html/defsnippet likes-model "templates/index.html" [:ul.things :> html/first-child]
  [{name :name id :id}]
  [:a] (html/set-attr :onclick (str "window.open('http://www.facebook.com/" id "')"))
  [:a :> :img] (html/do->
          (html/set-attr :alt name)
          (html/set-attr :src (str "https://graph.facebook.com/" id "/picture?type=square"))
          (html/after name)))

(html/defsnippet photos-model "templates/index.html" [:ul.photos :> html/first-child]
  [{picture :picture name :name id :id}]
  [:li] (html/set-attr :style (str "background-image: url(" picture ")"))
  [:li :> :a] (html/do->
          (html/set-attr :onclick (str "window.open('http://www.facebook.com/" id "')"))
          (html/content name)))

(html/defsnippet appfriends-model "templates/index.html" [:ul#appfriends :> html/first-child]
  [{pic_square :pic_square name :name uid :uid}]
  [:a] (html/set-attr :onclick (str "window.open('http://www.facebook.com/" uid "')"))
  [:a :> :img] (html/do->
          (html/set-attr :alt name)
          (html/set-attr :src pic_square)
          (html/after name)))

(html/deftemplate index "templates/index.html"
    [host app & [user friends photos likes appfriends]]
    [:title] (html/content (:name app))
    [(and 
      (html/has [:meta]) 
      (html/attr-has :property "og:url"))] (html/set-attr :content (str "https://" host "/"))
    [(and 
      (html/has [:meta]) 
      (html/attr-has :property "og:image"))] (html/set-attr :content (str "https://" host "/logo.png"))
    [(and 
      (html/has [:meta]) 
      (html/attr-has :property "og:title"))] (html/set-attr :content (:name app))
    [(and 
      (html/has [:meta]) 
      (html/attr-has :property "og:site_name"))] (html/set-attr :content (:name app))
    [(and 
      (html/has [:meta]) 
      (html/attr-has :property "fb:app_id"))] (html/set-attr :content (:id app))
    [:div.no-user (html/pred (fn [node] (not= user nil)))] nil
    [:div.user-logged-in (html/pred (fn [node] (= user nil)))] nil
    [:strong#username] (html/content (:name user))
    [:p#picture] (html/set-attr :style (str "background-image: url(https://graph.facebook.com/" (:id user) "/picture?type=normal)"))
    [:a#postToWall] (html/set-attr :data-url (str "https://" host "/"))
    [:a#sendToFriends] (html/set-attr :data-url (str "https://" host "/"))
    [:a#applink] (html/do->
                    (html/content (:name app))
                    (html/set-attr :href (:link app)))
    [:ul#myfriends] (html/content (map myfriends-model friends))
    [:ul.photos] (html/content (map photos-model photos))
    [:ul.photos :> (html/nth-child 4 1)] (html/add-class "first-column")
    [:ul#appfriends] (html/content (map appfriends-model appfriends))
    [:ul.things] (html/content (map likes-model likes)))

(defn render 
    "Helper function for rendering Enlive output"
    [t] (apply str t))
    
(def app-details 
  (read-json 
    (:body 
      (http-client/get 
        (str fb-helper/facebook-base-url "/" (System/getenv "FACEBOOK_APP_ID"))))))

(defroutes handler
  (GET "/" {headers :headers} 
       (if (not *facebook-auth*)
         ;; fb-client doesn't like getting app info without the access token
         (render (index (get headers "host") app-details))
         (let [app (fb-client/get [:app] {:extract :body})
               user (fb-client/get [:me] {:extract :body})
               friends (fb-client/get [:me :friends] {:query-params {:limit 4} :extract :data})
               photos (fb-client/get [:me :photos] {:query-params {:limit 16} :extract :data})
               likes (fb-client/get [:me :likes] {:query-params {:limit 4} :extract :data})
               appfriends (:body (fb-client/get :fql {:fql "SELECT uid, name, is_app_user, pic_square FROM user WHERE uid in (SELECT uid2 FROM friend WHERE uid1 = me()) AND is_app_user = 1"}))]
;(println (str "user: " user "\napp: " app "\nfriends: " friends "\nphotos: " photos "\nlikes: " likes "\nappfriends: " appfriends))
(println (str "headers: " headers))
              (render (index (get headers "host") app user friends photos likes appfriends)))))
  (GET "/auth/facebook" [] 
    (if (not *facebook-auth*)
      (throw
       (FacebookGraphException.
        {:error :facebook-login-required}))
      {:status 302
       :headers {"Location" "/"}}))
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
