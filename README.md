### Tired of pinned messages limit? Only 50 is not enough for you ? Install new auto-pin-bot on your server!
### It will create a separate channel where all the pinned messages are stored, in a pretty format.
:warning:  **Bot doesn't track or save any information from your server!**
# For server admins:
Just invite bot to your server:

https://discord.com/oauth2/authorize?client_id=698545934170194012&permissions=805415952&scope=bot

# For developers:
If you considered about your privacy, or for some reasons want to deploy this bot for youself? There are docker-compose file that helps you easly bootstrap that bot.

```shell script
 # create enviroment file with your bot token
 $ echo BOT_TOKEN=<YOUR_BOT_TOKEN> > .env
 # bootstrap docker image
 $ docker-compose up -d 
```
