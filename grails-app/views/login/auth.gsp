<head>
  <meta name='layout' content='main' />
</head>
<body>
<div class='body'>
  <br><br><br>
  <img src="${resource(dir:'images',file:'login.png')}" alt="">

<div
        style="overflow: hidden; display: block; z-index: 1002; outline-color: -moz-use-text-color; outline-style: none; outline-width: 0px; height: auto; width: 230px; float: right;"
        class="ui-dialog ui-widget ui-widget-content ui-corner-all" tabindex="-1" role="dialog" aria-labelledby="ui-dialog-title-dialog">
<form method="post" id="loginForm" action="${postUrl}">
  <div class="ui-dialog-titlebar ui-widget-header ui-corner-all ui-helper-clearfix" unselectable="on" style="-moz-user-select: none;">
    <span class="ui-dialog-title" id="ui-dialog-title-dialog" unselectable="on" style="-moz-user-select: none;">Login</span>
  </div>
  <div id="dialog" class="ui-dialog-content ui-widget-content" style="height: auto; min-height: 64px; width: auto;">
    <g:if test='${flash.message}'>
    <p id="validateTips">
      <div class='errors'>${flash.message}</div>
    </p>
    </g:if>
    <fieldset>
      <label for="j_username">Username:</label>
      <input type="text" id="j_username" name="j_username" value="" maxlength="50"/>
      <label for="j_password">Password:</label>
      <input type="password" id="j_password" name="j_password" value="" maxlength="50"/>
      <label for='remember_me'>Remember me</label>
      <input type='checkbox' class='chk' name='_spring_security_remember_me' id='remember_me' <g:if test='${hasCookie}'>checked='checked'</g:if> />
    </fieldset>
</div>
  <div class="ui-dialog-buttonpane ui-widget-content ui-helper-clearfix">
    <input type="submit" class="ui-state-default ui-corner-all" value="Ok">
    <input type="button" class="ui-state-default ui-corner-all" value="Register!" onclick="register();">
  </div>
</form>
</div>
</div>
<script type='text/javascript'>
  function register(){
    window.location = "../register";
  }
<!--
(function(){
	document.forms['loginForm'].elements['j_username'].focus();
})();
// -->
</script>
</body>
