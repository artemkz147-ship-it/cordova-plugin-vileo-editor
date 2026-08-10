var exec=require('cordova/exec');
module.exports={
 pickVideos:function(ok,fail){exec(ok,fail,'VileoEditor','pickVideos',[]);},
 pickAudio:function(ok,fail){exec(ok,fail,'VileoEditor','pickAudio',[]);},
 pickImage:function(ok,fail){exec(ok,fail,'VileoEditor','pickImage',[]);},
 getMediaInfo:function(path,ok,fail){exec(ok,fail,'VileoEditor','getMediaInfo',[path]);},
 exportProject:function(project,ok,fail){exec(ok,fail,'VileoEditor','exportProject',[project||{}]);},
 getProgress:function(ok,fail){exec(ok,fail,'VileoEditor','getProgress',[]);},
 cancelExport:function(ok,fail){exec(ok,fail,'VileoEditor','cancelExport',[]);},
 cleanupCache:function(ok,fail){exec(ok,fail,'VileoEditor','cleanupCache',[]);}
};