Facebook/Heroku sample app -- Clojure
=====================================

This is a sample app showing use of the Facebook Graph API, written in Clojure, designed for deployment to [Heroku](http://www.heroku.com/). You can see the sample running at https://facebook-template-clojure.herokuapp.com/.

Run locally
-----------

[Create an app on Facebook](https://developers.facebook.com/apps) and set the Website URL to `http://localhost:5000/`.

Copy the App ID and Secret from the Facebook app settings page into your `.env`:

    echo FACEBOOK_APP_ID=12345 >> .env
    echo FACEBOOK_SECRET=abcde >> .env
    
Set the redirect URI:

    echo REDIRECT_URI="http://localhost:5000/facebook-callback" >> .env

Launch the app with [Foreman](http://blog.daviddollar.org/2011/05/06/introducing-foreman.html):

    foreman start

Deploy to Heroku via Facebook integration
-----------------------------------------

The easiest way to deploy is to create an app on Facebook and click Cloud Services -> Get Started, then choose Clojure from the dropdown.  You can then `git clone` the resulting app from Heroku.

Deploy to Heroku directly
-------------------------

If you prefer to deploy yourself, push this code to a new Heroku app on the Cedar stack, then copy the App ID and Secret into your config vars:

    heroku create --stack cedar
    git push heroku master
    heroku config:add FACEBOOK_APP_ID=12345 FACEBOOK_SECRET=abcde REDIRECT_URI="https://your-heroku-app-name/facebook-callback"

Enter the URL for your Heroku app into the Website URL section of the Facebook app settings page, then you can visit your app on the web.

