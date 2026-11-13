package com.bridge.quartercore;
final class ManagedIdentityValidation {
  static void validate(String issuer,String subject,String nickname,String email,boolean profile){
    if(!"https://accounts.google.com".equals(issuer)||subject==null||subject.isBlank()||subject.length()>255||!subject.equals(subject.trim())||subject.contains("@")||subject.contains("*")||subject.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid verified identity");
    if(profile&&(!AccountProfileRows.validNickname(nickname)||!AccountProfileRows.validEmail(email)))throw new IllegalArgumentException("Invalid account profile");
  }
}
