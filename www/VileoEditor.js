var exec = require('cordova/exec');
module.exports = {
  pickVideo: function(success, error){ exec(success, error, 'VileoEditor', 'pickVideo', []); },
  getVideoInfo: function(path, success, error){ exec(success, error, 'VileoEditor', 'getVideoInfo', [path]); },
  exportVideo: function(options, success, error){ exec(success, error, 'VileoEditor', 'exportVideo', [options || {}]); },
  getProgress: function(success, error){ exec(success, error, 'VileoEditor', 'getProgress', []); },
  cancelExport: function(success, error){ exec(success, error, 'VileoEditor', 'cancelExport', []); }
};
