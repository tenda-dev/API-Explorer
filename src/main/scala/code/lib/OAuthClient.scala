/**
Open Bank Project - Transparency / Social Finance Web Application
Copyright (C) 2011, 2012, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */

package code.lib

import net.liftweb.http.SessionVar
import net.liftweb.common.Box
import net.liftweb.common.Empty
import oauth.signpost.OAuthProvider
import oauth.signpost.basic.DefaultOAuthProvider
import net.liftweb.util.Props
import net.liftweb.http.S
import oauth.signpost.OAuthConsumer
import oauth.signpost.basic.DefaultOAuthConsumer
import net.liftweb.mapper.By
import net.liftweb.common.{Full, Failure}
import net.liftweb.util.Helpers
import net.liftweb.http.LiftResponse
import code.util.Helper.MdcLoggable

sealed trait Provider {
  val name : String

  val apiBaseUrl : String
  val requestTokenUrl : String
  val accessTokenUrl : String
  val authorizeUrl : String
  val signupUrl : Option[String]

  /**
   * Can't do oAuthProvider = new DefaultOAuthProvider(requestTokenUrl, accessTokenUrl, authorizeUrl)
   * here as the Strings all evaluate at null at this point in object creation
   */
  val oAuthProvider : OAuthProvider

  val consumerKey : String
  val consumerSecret : String
}

trait DefaultProvider extends Provider with MdcLoggable {
  val name = "The Open Bank Project Demo"
  
  // val baseUrl = Props.get("oauth_1.hostname").getOrElse(Props.get("api_hostname", S.hostName))
  val oauthBaseUrl = Props.get("oauth_1.hostname") match {
    case Full(v) =>
      v
    case _ =>
      logger.warn("==========>> THERE IS NO THE VALUE FOR PROPS oauth_1.hostname <<====================")
      Props.get("api_hostname") match {
      case Full(v) => 
        v
      case _ =>
        logger.warn("==========>> THERE IS NO THE VALUE FOR PROPS api_hostname <<====================")
        logger.warn("==========>> DEFAULT VALUE: " + S.hostName + " <<====================")
        S.hostName
    }
  }
  // To link to API home page (this is duplicated in OAuthClient)
  val baseUrl = Props.get("api_hostname", S.hostName)
  val apiBaseUrl = baseUrl + "" // Was "/obp"
  val requestTokenUrl = oauthBaseUrl + "/oauth/initiate"
  val accessTokenUrl = oauthBaseUrl + "/oauth/token"
  val authorizeUrl = oauthBaseUrl + "/oauth/authorize"
  val signupUrl = Some(oauthBaseUrl + "/user_mgt/sign_up")

  lazy val oAuthProvider : OAuthProvider = new DefaultOAuthProvider(requestTokenUrl, accessTokenUrl, authorizeUrl)

  val consumerKey = Props.get("obp_consumer_key", "")
  val consumerSecret = Props.get("obp_secret_key", "")
}

object OBPDemo extends DefaultProvider

object AddBankAccountProvider extends DefaultProvider {
  override val name = "The Open Bank Project Demo - Add Bank Account"

  //The "login" prefix before /oauth means that we will use the oauth flow that will ask the user
  //to connect a bank account
  override val requestTokenUrl = oauthBaseUrl + "/login/oauth/initiate"
  override val accessTokenUrl = oauthBaseUrl + "/login/oauth/token"
  override val authorizeUrl = oauthBaseUrl + "/login/oauth/authorize"
}

case class Consumer(consumerKey : String, consumerSecret : String) {
  val oAuthConsumer : OAuthConsumer = new DefaultOAuthConsumer(consumerKey, consumerSecret)
}

case class Credential(provider : Provider, consumer : OAuthConsumer, readyToSign : Boolean)

object credentials extends SessionVar[Option[Credential]](None)
object mostRecentLoginAttemptProvider extends SessionVar[Box[Provider]](Empty)

object OAuthClient extends MdcLoggable {

  def getAuthorizedCredential() : Option[Credential] = {
    credentials.filter(_.readyToSign)
  }

  def currentApiBaseUrl : String = {
    getAuthorizedCredential().map(_.provider.apiBaseUrl).getOrElse(OBPDemo.apiBaseUrl)
  }

  def setNewCredential(provider : Provider) : Credential = {
    val consumer = new DefaultOAuthConsumer(provider.consumerKey, provider.consumerSecret)
    val credential = Credential(provider, consumer, false)

    credentials.set(Some(credential))
    credential
  }

  def handleCallback(): Box[LiftResponse] = {

    val success = for {
      verifier <- S.param("oauth_verifier") ?~ "No oauth verifier found"
      provider <- mostRecentLoginAttemptProvider.get ?~ "No provider found for callback"
      consumer <- Box(credentials.map(_.consumer)) ?~ "No consumer found for callback"
    } yield {
      //after this, consumer is ready to sign requests
      provider.oAuthProvider.retrieveAccessToken(consumer, verifier)
      //update the session credentials
      val newCredential = Credential(provider, consumer, true)
      credentials.set(Some(newCredential))
    }

    success match {
      case Full(_) => S.redirectTo("/") //TODO: Allow this redirect to be customised
      case Failure(msg, _, _) => logger.warn(msg)
      case _ => logger.warn("Something went wrong in an oauth callback and there was no error message set for it")
    }
    Empty
  }

  def redirectToOauthLogin() = {
    redirect(OBPDemo)
  }

  private def redirect(provider : Provider) = {
    mostRecentLoginAttemptProvider.set(Full(provider))
    val credential = setNewCredential(provider)

    val oauthcallbackUrl = Props.get("base_url", S.hostName) + "/oauthcallback"
    val authUrl = provider.oAuthProvider.retrieveRequestToken(credential.consumer, oauthcallbackUrl)
    //eg: authUrl = http://127.0.0.1:8080/oauth/authorize?oauth_token=LK5N1WBQZGXHMQXJT35KDHAJXUP1EMQCGBQFQQNG
    //This step is will call `provider.authorizeUrl = baseUrl + "/oauth/authorize"` endpoint, and get the request token back.
    logger.debug("oauth.provider.name            = " + provider.name           )        
    logger.debug("oauth.provider.apiBaseUrl      = " + provider.apiBaseUrl     )        
    logger.debug("oauth.provider.requestTokenUrl = " + provider.requestTokenUrl)        
    logger.debug("oauth.provider.accessTokenUrl  = " + provider.accessTokenUrl )        
    logger.debug("oauth.provider.authorizeUrl    = " + provider.authorizeUrl   )        
    logger.debug("oauth.provider.signupUrl       = " + provider.signupUrl      )        
    logger.debug("oauth.provider.oAuthProvider.getRequestTokenEndpointUrl   = " + provider.oAuthProvider.getRequestTokenEndpointUrl  )     //http://127.0.0.1:8080/oauth/initiate    
    logger.debug("oauth.provider.oAuthProvider.getAccessTokenEndpointUrl   = " + provider.oAuthProvider.getAccessTokenEndpointUrl  )     //http://127.0.0.1:8080/oauth/token    
    logger.debug("oauth.provider.oAuthProvider.getAuthorizationWebsiteUrl   = " + provider.oAuthProvider.getAuthorizationWebsiteUrl  )    //http://127.0.0.1:8080/oauth/authorize     
    logger.debug("oauth.provider.consumerKey     = " + provider.consumerKey    )        
    logger.debug("oauth.provider.consumerSecret  = " + provider.consumerSecret )        
    logger.debug("oauth.credential.getConsumerKey = " + credential.consumer.getConsumerKey)      
    logger.debug("oauth.credential.getConsumerSecret = " + credential.consumer.getConsumerSecret)      
    logger.debug("oauth.oauthcallbackUrl = " + oauthcallbackUrl)
    
    S.redirectTo(authUrl)
  }

  def redirectToConnectBankAccount() = {
    redirect(AddBankAccountProvider)
  }

  def loggedIn : Boolean = credentials.map(_.readyToSign).getOrElse(false)

  def logoutAll() = {
    credentials.set(None)
    S.redirectTo("/")
  }
}